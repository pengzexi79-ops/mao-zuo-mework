package com.douyin.mixcut.repository;

import com.douyin.mixcut.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;

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
