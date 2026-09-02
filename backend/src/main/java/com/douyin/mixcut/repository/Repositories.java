package com.douyin.mixcut.repository;

import com.douyin.mixcut.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 仓储层集中声明（接口体量小，集中一处便于通读，避免十几个单文件）。
 */
public interface Repositories {

    interface MaterialFolderRepo extends JpaRepository<MaterialFolder, Long> {
        Optional<MaterialFolder> findByPath(String path);
        Optional<MaterialFolder> findFirstByNameIgnoreCaseAndParentIdIsNull(String name);
        Optional<MaterialFolder> findFirstByNameIgnoreCaseAndParentId(String name, Long parentId);
        List<MaterialFolder> findAllByOrderBySortOrderAscIdAsc();
    }

    interface AiProviderRepo extends JpaRepository<AiProvider, Long> {
        List<AiProvider> findByEnabledTrueOrderByPriorityAsc();

        Optional<AiProvider> findByName(String name);
    }

    interface AiRouteRepo extends JpaRepository<AiRoute, Long> {
        Optional<AiRoute> findByUseCase(String useCase);
    }

    interface AiLogRepo extends JpaRepository<AiLog, Long> {
        List<AiLog> findTop200ByOrderByIdDesc();
    }

    interface ProjectRepo extends JpaRepository<Project, Long> {
        Optional<Project> findByName(String name);
    }

    interface WorkflowRepo extends JpaRepository<Workflow, Long> {
        Optional<Workflow> findByName(String name);

        List<Workflow> findAllByOrderByIdAsc();
    }

    interface SkillDefRepo extends JpaRepository<SkillDef, Long> {
        List<SkillDef> findByEnabledTrue();

        Optional<SkillDef> findByName(String name);
    }

    interface JobRepo extends JpaRepository<Job, Long> {
        List<Job> findTop100ByOrderByIdDesc();
        List<Job> findTop100ByProjectIdOrderByIdDesc(Long projectId);

        List<Job> findByStatusOrderByIdAsc(String status);

        List<Job> findByStatus(String status);

        long countByStatus(String status);

        @Transactional
        @Modifying(flushAutomatically = true, clearAutomatically = true)
        @Query("update Job j set j.status = 'running', j.version = j.version + 1, j.executionEpoch = j.executionEpoch + 1, j.leaseToken = :token, "
                + "j.leaseExpiresAt = :expiresAt, j.lastActivityAt = :now where j.id = :id and j.status = 'pending'")
        int claimPendingJob(@Param("id") Long id, @Param("token") String token,
                            @Param("now") LocalDateTime now, @Param("expiresAt") LocalDateTime expiresAt);

        @Transactional
        @Modifying(flushAutomatically = true, clearAutomatically = true)
        @Query("update Job j set j.lastActivityAt = :now, j.currentStep = :step, j.phaseProgress = :phaseProgress, "
                + "j.leaseExpiresAt = :expiresAt where j.id = :id and j.status = 'running' "
                + "and j.executionEpoch = :epoch and j.leaseToken = :token")
        int heartbeatOwnedJob(@Param("id") Long id, @Param("epoch") Long epoch, @Param("token") String token,
                              @Param("step") String step, @Param("phaseProgress") Integer phaseProgress,
                              @Param("now") LocalDateTime now, @Param("expiresAt") LocalDateTime expiresAt);

        @Transactional
        @Modifying(flushAutomatically = true, clearAutomatically = true)
        @Query("update Job j set j.version = j.version + 1, j.executionEpoch = j.executionEpoch + 1, j.leaseToken = null, j.leaseExpiresAt = null "
                + "where j.id = :id and j.status in :statuses")
        int invalidateLease(@Param("id") Long id, @Param("statuses") List<String> statuses);

        @Transactional
        @Modifying(flushAutomatically = true, clearAutomatically = true)
        @Query("update Job j set j.status = 'failed', j.summary = :summary, j.error = :error, "
                + "j.version = j.version + 1, j.lastActivityAt = :now, j.leaseToken = null, j.leaseExpiresAt = null, j.executionEpoch = j.executionEpoch + 1 "
                + "where j.id = :id and j.status = 'running' and "
                + "(j.lastActivityAt <= :activityBefore or j.leaseExpiresAt <= :now)")
        int failStaleRunningJob(@Param("id") Long id, @Param("summary") String summary, @Param("error") String error,
                                @Param("now") LocalDateTime now, @Param("activityBefore") LocalDateTime activityBefore);

        @Transactional
        @Modifying(flushAutomatically = true, clearAutomatically = true)
        @Query("update Job j set j.status = 'failed', j.summary = :summary, j.error = :error, "
                + "j.version = j.version + 1, j.lastActivityAt = :now, j.leaseToken = null, j.leaseExpiresAt = null, j.executionEpoch = j.executionEpoch + 1 "
                + "where j.id = :id and j.status = 'running' and j.createdAt <= :createdBefore")
        int failTimedOutRunningJob(@Param("id") Long id, @Param("summary") String summary, @Param("error") String error,
                                   @Param("now") LocalDateTime now, @Param("createdBefore") LocalDateTime createdBefore);

        @Transactional
        @Modifying(flushAutomatically = true, clearAutomatically = true)
        @Query("update Job j set j.status = 'cancelled', j.version = j.version + 1, j.executionEpoch = j.executionEpoch + 1, "
                + "j.leaseToken = null, j.leaseExpiresAt = null, j.current = :current, j.progress = :progress, "
                + "j.summary = :summary, j.error = null, j.lastActivityAt = :now "
                + "where j.id = :id and j.status in :statuses")
        int transitionCancelled(@Param("id") Long id, @Param("statuses") List<String> statuses,
                                @Param("current") Integer current, @Param("progress") Integer progress,
                                @Param("summary") String summary, @Param("now") LocalDateTime now);

        @Transactional
        @Modifying(flushAutomatically = true, clearAutomatically = true)
        @Query("update Job j set j.status = 'paused', j.version = j.version + 1, j.executionEpoch = j.executionEpoch + 1, "
                + "j.leaseToken = null, j.leaseExpiresAt = null, j.current = :current, j.progress = :progress, "
                + "j.summary = :summary, j.error = null, j.lastActivityAt = :now "
                + "where j.id = :id and j.status in :statuses")
        int transitionPaused(@Param("id") Long id, @Param("statuses") List<String> statuses,
                             @Param("current") Integer current, @Param("progress") Integer progress,
                             @Param("summary") String summary, @Param("now") LocalDateTime now);

        @Transactional
        @Modifying(flushAutomatically = true, clearAutomatically = true)
        @Query("update Job j set j.status = 'awaiting_decision', j.version = j.version + 1, j.executionEpoch = j.executionEpoch + 1, "
                + "j.leaseToken = null, j.leaseExpiresAt = null, j.current = :current, j.progress = :progress, "
                + "j.summary = :summary, j.error = :error, j.currentStep = :currentStep, j.lastActivityAt = :now "
                + "where j.id = :id and j.status = 'running' and j.executionEpoch = :epoch and j.leaseToken = :token")
        int transitionAwaitingDecision(@Param("id") Long id, @Param("epoch") Long epoch, @Param("token") String token,
                                       @Param("current") Integer current, @Param("progress") Integer progress,
                                       @Param("summary") String summary, @Param("error") String error,
                                       @Param("currentStep") String currentStep, @Param("now") LocalDateTime now);
    }

    interface CrawlJobRepo extends JpaRepository<CrawlJob, Long> {
        List<CrawlJob> findTop50ByOrderByIdDesc();
        List<CrawlJob> findByStatusOrderByIdAsc(String status);
    }

    interface CrawlTaskRepo extends JpaRepository<CrawlTask, Long> {
        List<CrawlTask> findByJobIdOrderByIdxAsc(Long jobId);
        Optional<CrawlTask> findByJobIdAndIdx(Long jobId, Integer idx);
        long countByJobIdAndStatus(Long jobId, String status);
        void deleteByJobId(Long jobId);
        List<CrawlTask> findByMaterialId(Long materialId);
    }

    interface PreparationTaskRepo extends JpaRepository<PreparationTask, Long> {
        List<PreparationTask> findTop20ByOrderByIdDesc();
        List<PreparationTask> findByStatusOrderByIdAsc(String status);
    }

    interface MediaTaskRepo extends JpaRepository<MediaTask, Long> {
        Optional<MediaTask> findByTaskKey(String taskKey);
        List<MediaTask> findTop50ByOrderByIdDesc();
        List<MediaTask> findByStatusOrderByIdAsc(String status);
        long countByStatus(String status);
        @Modifying
        @Transactional
        @Query("update MediaTask m set m.status = 'pending', m.phase = 'recovering', "
                + "m.recoveryState = 'requeued', m.recoveryReason = :reason, m.lastActivityAt = :now "
                + "where m.taskKey = :taskKey and m.status = 'running' "
                + "and (m.lastActivityAt <= :cutoff or m.lastActivityAt is null)")
        int claimStaleForRecovery(@Param("taskKey") String taskKey,
                                  @Param("cutoff") LocalDateTime cutoff,
                                  @Param("reason") String reason,
                                  @Param("now") LocalDateTime now);
    }

    interface MediaGenerationTaskRepo extends JpaRepository<MediaGenerationTask, Long> {
        Optional<MediaGenerationTask> findByTaskKey(String taskKey);
        List<MediaGenerationTask> findTop50ByOrderByIdDesc();
        List<MediaGenerationTask> findByStatusNotInOrderByIdDesc(List<String> statuses);
        List<MediaGenerationTask> findByStatusOrderByIdAsc(String status);
        List<MediaGenerationTask> findByPhaseAndRemoteTaskIdIsNotNullOrderByIdAsc(String phase);
        List<MediaGenerationTask> findByPhaseInOrderByIdAsc(List<String> phases);
        Optional<MediaGenerationTask> findByIdempotencyKey(String idempotencyKey);
    }

    interface JobOutputRepo extends JpaRepository<JobOutput, Long> {
        List<JobOutput> findByJobIdOrderByIdxAsc(Long jobId);

        Optional<JobOutput> findByJobIdAndIdx(Long jobId, Integer idx);

        List<JobOutput> findTop200ByOrderByIdDesc();

        void deleteByJobId(Long jobId);
    }

    interface OutputVersionRepo extends JpaRepository<OutputVersion, Long> {
        List<OutputVersion> findByJobIdAndIdxOrderByVersionNoAsc(Long jobId, Integer idx);
        List<OutputVersion> findByJobIdOrderByIdxAscVersionNoAsc(Long jobId);
        Optional<OutputVersion> findByJobIdAndIdxAndVersionNo(Long jobId, Integer idx, Integer versionNo);
        Optional<OutputVersion> findTopByJobIdAndIdxOrderByVersionNoDesc(Long jobId, Integer idx);
        void deleteByJobId(Long jobId);
    }

    interface OutputRepairRepo extends JpaRepository<OutputRepair, Long> {
        List<OutputRepair> findByJobIdAndIdxOrderByIdAsc(Long jobId, Integer idx);
        List<OutputRepair> findByOutputVersionIdOrderByIdAsc(Long outputVersionId);
        void deleteByJobId(Long jobId);
    }

    interface OutputEditSessionRepo extends JpaRepository<OutputEditSession, Long> {
        Optional<OutputEditSession> findTopByJobIdAndIdxOrderByUpdatedAtDesc(Long jobId, Integer idx);
    }

    interface PluginRepo extends JpaRepository<AppPlugin, Long> {
        List<AppPlugin> findAllByOrderByPriorityAscIdAsc();

        Optional<AppPlugin> findByKey(String key);
    }
}
