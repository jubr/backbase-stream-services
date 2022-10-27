package com.backbase.stream.worker;


import com.backbase.stream.worker.configuration.StreamWorkerConfiguration;
import com.backbase.stream.worker.model.StreamTask;
import com.backbase.stream.worker.model.TaskHistory;
import com.backbase.stream.worker.model.UnitOfWork;
import com.backbase.stream.worker.repository.UnitOfWorkRepository;
import com.backbase.stream.worker.repository.impl.InMemoryReactiveUnitOfWorkRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.sleuth.annotation.ContinueSpan;
import org.springframework.cloud.sleuth.annotation.NewSpan;
import org.springframework.cloud.sleuth.annotation.SpanTag;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.OffsetDateTime;
import java.util.stream.Collectors;

@Slf4j
public abstract class UnitOfWorkExecutor<T extends StreamTask> {

    private final UnitOfWorkRepository<T, String> repository;
    private final StreamTaskExecutor<T> streamTaskExecutor;
    private final Scheduler taskExecutor;

    protected final StreamWorkerConfiguration streamWorkerConfiguration;

    public UnitOfWorkExecutor(UnitOfWorkRepository<T, String> repository, StreamTaskExecutor<T> streamTaskExecutor,
        StreamWorkerConfiguration streamWorkerConfiguration) {
        this.repository = repository;
        this.streamTaskExecutor = streamTaskExecutor;
        this.taskExecutor = Schedulers.newParallel("TaskScheduler", streamWorkerConfiguration.getTaskExecutors());
        this.streamWorkerConfiguration = streamWorkerConfiguration;
    }

    public Mono<UnitOfWork<T>> register(UnitOfWork<T> unitOfWork) {
        log.info("Registering Unit Of Work: {}", unitOfWork.getUnitOfOWorkId());
        unitOfWork.setRegisteredAt(OffsetDateTime.now());
        unitOfWork.setNextAttemptAt(OffsetDateTime.now());
        unitOfWork.setState(UnitOfWork.State.ACCEPTED);
        return repository.save(unitOfWork);
    }

    public Mono<UnitOfWork<T>> retrieve(String unitOfWorkId) {
        return repository.findById(unitOfWorkId);
    }

    private Mono<UnitOfWork<T>> cleanup(UnitOfWork<T> unitOfWork) {
        // Clean up completed/errored out unit of works from the InMemoryRepository
        if (repository instanceof InMemoryReactiveUnitOfWorkRepository) {
            log.info("Cleaning up Unit Of Work: {}", unitOfWork.getUnitOfOWorkId());
            return repository.delete(unitOfWork)
                    .thenReturn(unitOfWork);
        }
        return Mono.just(unitOfWork);
    }

    private Mono<UnitOfWork<T>> complete(UnitOfWork<T> unitOfWork) {
        log.info("Completing Unit Of Work: {}", unitOfWork.getUnitOfOWorkId());
        unitOfWork.setLockedAt(null);
        unitOfWork.setFinishedAt(OffsetDateTime.now());

        boolean failed = unitOfWork.getStreamTasks().stream().anyMatch(StreamTask::isFailed);

        if (failed) {
            int retries = unitOfWork.getRetries();
            if (retries < streamWorkerConfiguration.getMaxRetries()) {
                unitOfWork.setNextAttemptAt(
                    OffsetDateTime.now().plusSeconds(streamWorkerConfiguration.getRetryDuration().getSeconds()));
                unitOfWork.setRetries(retries + 1);
                unitOfWork.setState(UnitOfWork.State.FAILED);
                unitOfWork.setLockedAt(null);
            } else {
                unitOfWork.setNextAttemptAt(null);
                unitOfWork.setState(UnitOfWork.State.FAILED_RETRIES_EXHAUSTED);
            }

        } else {
            unitOfWork.setNextAttemptAt(null);
            unitOfWork.setState(UnitOfWork.State.COMPLETED);
        }

        return repository.save(unitOfWork);
    }


    @NewSpan
    public Mono<UnitOfWork<T>> executeUnitOfWork(UnitOfWork<T> unitOfWork) {
        return Mono.just(unitOfWork)
            .flatMap(this::setLocked)
            .flatMap(this::executeTasks)
            .flatMap(this::complete)
            .doFinally(r -> cleanup(unitOfWork));
    }

    @ContinueSpan(log = "Locking Unit Of Work")
    private Mono<UnitOfWork<T>> setLocked(
        @SpanTag(value = "unit-of-work", expression = "${unitOfWork.unitOfOWorkId}") UnitOfWork<T> unitOfWork) {
        log.info("Locking Unit Of Work: {}", unitOfWork.getUnitOfOWorkId());
        unitOfWork.setLockedAt(OffsetDateTime.now());
        unitOfWork.setState(UnitOfWork.State.IN_PROGRESS);
        return repository.save(unitOfWork);
    }


    public Mono<UnitOfWork<T>> executeTasks(UnitOfWork<T> unitOfWork) {
        return Flux.fromIterable(unitOfWork.getStreamTasks())
            .publishOn(taskExecutor)
            .name("task-executor")
            .tag("stream-unit-of-work-id", unitOfWork.getUnitOfOWorkId())
            .map(streamTask -> startTask(unitOfWork, streamTask))
            .flatMap(streamTask -> executeTask(unitOfWork, streamTask, streamTask.getId()))
            .map(streamTask -> endTask(unitOfWork, streamTask))
            .collectList()
            .zipWith(Mono.just(unitOfWork), (tasks, actual) -> actual);
    }


    private Mono<T> executeTask(UnitOfWork<T> unitOfWork, T streamTask, @SpanTag("stream-task") String streamTaskId) {
        return streamTaskExecutor.executeTask(streamTask)
            .map(actual -> {
                actual.setState(StreamTask.State.COMPLETED);
                return actual;
            })
            .onErrorResume(Throwable.class, throwable -> {
                streamTask.setState(StreamTask.State.FAILED);
                streamTask.setError(throwable.getMessage());
                log.error("Stream Task: {} from Unit Of Work: {} failed: \n{}",
                    streamTaskId,
                    streamTask.getId(),
                    streamTask.getHistory().stream().map(TaskHistory::toString)
                        .collect(Collectors.joining("\n")));

                return Mono.just(streamTask);
            });
    }

    private T startTask(UnitOfWork<T> unitOfWork, T streamTask) {
        log.info("Starting Task: {} from Unit Of Work: {}", streamTask.getId(), unitOfWork.getUnitOfOWorkId());
        streamTask.setState(StreamTask.State.IN_PROGRESS);
        streamTask.setRegisteredAt(OffsetDateTime.now());
        return streamTask;
    }

    private T endTask(UnitOfWork<T> unitOfWork, T streamTask) {
        log.info("Ending Task: {} from Unit Of Work: {}", streamTask.getId(), unitOfWork.getUnitOfOWorkId());
        streamTask.setFinishedAt(OffsetDateTime.now());
        return streamTask;
    }

    public StreamWorkerConfiguration getStreamWorkerConfiguration() {
        return streamWorkerConfiguration;
    }
}
