package com.shijie.resumehelper.material.store;

import com.shijie.resumehelper.material.domain.ExtractedProfile;
import com.shijie.resumehelper.material.domain.Material;

import java.util.List;
import java.util.Optional;

/**
 * 材料的持久化。
 *
 * <p>和岗位模块的 {@code JobStore} 一样是接口：当前实现是文件系统
 * （零外部依赖，克隆下来就能跑，演示机器上也不用装数据库），
 * 将来换成 MySQL 只需新增一个实现类，服务和接口层不用改。
 */
public interface MaterialStore {

    /** 保存原文件与元数据。 */
    Material saveOriginal(Material material, byte[] bytes);

    /** 保存抽出的原文。 */
    void saveText(String userId, String materialId, String text);

    /** 读取抽出的原文，用于「原文对照」。 */
    Optional<String> readText(String userId, String materialId);

    /** 保存结构化抽取结果。 */
    void saveExtraction(String userId, String materialId, ExtractedProfile profile);

    /** 读取结构化抽取结果。 */
    Optional<ExtractedProfile> readExtraction(String userId, String materialId);

    /** 按 id 查材料。必须带 userId，避免越权读到别人的材料。 */
    Optional<Material> find(String userId, String materialId);

    /** 列出该用户的所有材料，最近上传的在前。 */
    List<Material> list(String userId);

    /** 删除材料（原文件、原文、抽取结果一并删掉）。 */
    boolean delete(String userId, String materialId);
}
