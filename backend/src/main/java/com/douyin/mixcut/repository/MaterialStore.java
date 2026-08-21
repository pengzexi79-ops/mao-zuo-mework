package com.douyin.mixcut.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.douyin.mixcut.domain.Material;
import com.douyin.mixcut.domain.MaterialRole;
import com.douyin.mixcut.mapper.MaterialMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class MaterialStore {

    private final MaterialMapper mapper;

    public Optional<Material> findById(Long id) {
        return Optional.ofNullable(mapper.selectById(id));
    }

    public Optional<Material> findByFilePath(String filePath) {
        return mapper.selectList(query().eq(Material::getFilePath, filePath)
                        .orderByDesc(Material::getId).last("LIMIT 1"))
                .stream().findFirst();
    }

    public boolean existsByFilePath(String filePath) {
        return mapper.selectCount(query().eq(Material::getFilePath, filePath)) > 0;
    }

    public long countByFilePath(String filePath) {
        return mapper.selectCount(query().eq(Material::getFilePath, filePath));
    }

    public Material save(Material material) {
        if (material.getId() == null) {
            if (material.getCreatedAt() == null) material.setCreatedAt(LocalDateTime.now());
            mapper.insert(material);
        } else {
            mapper.updateById(material);
        }
        return material;
    }

    public List<Material> findAll() {
        return mapper.selectList(query());
    }

    public List<Material> findAllByOrderByIdDesc() {
        return mapper.selectList(query().orderByDesc(Material::getId));
    }

    public long countByFolderId(Long folderId) {
        return mapper.selectCount(query().eq(Material::getFolderId, folderId));
    }

    public List<Material> findByRole(MaterialRole role) {
        return mapper.selectList(query().eq(Material::getRole, role));
    }

    public List<Material> findByFileType(Material.FileType fileType) {
        return mapper.selectList(query().eq(Material::getFileType, fileType));
    }

    public List<Material> findByStatus(Material.Status status) {
        return mapper.selectList(query().eq(Material::getStatus, status));
    }

    public List<Material> findByRoleAndFileType(MaterialRole role, Material.FileType fileType) {
        return mapper.selectList(query()
                .eq(Material::getRole, role)
                .eq(Material::getFileType, fileType));
    }

    public long count() {
        return mapper.selectCount(query());
    }

    public long countByRole(MaterialRole role) {
        return mapper.selectCount(query().eq(Material::getRole, role));
    }

    public long countByFileType(Material.FileType fileType) {
        return mapper.selectCount(query().eq(Material::getFileType, fileType));
    }

    public void delete(Material material) {
        if (material != null && material.getId() != null) mapper.deleteById(material.getId());
    }

    public void deleteById(Long id) {
        mapper.deleteById(id);
    }

    private LambdaQueryWrapper<Material> query() {
        return new LambdaQueryWrapper<>();
    }
}
