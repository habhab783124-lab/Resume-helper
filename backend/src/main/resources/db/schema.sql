-- ============================================================================
-- 拾阶求职工作台 · 岗位模块（功能二）MySQL 表结构
--
-- 说明：
-- 1) 岗位模块当前用 InMemoryJobStore 就能跑通全流程，不依赖数据库；
--    这份脚本是给「接入 MySQL」用的，实现 JobStore 接口即可无缝替换。
-- 2) 统一 utf8mb4：岗位描述和技能里会有 emoji 和生僻字。
-- 3) JSON 列需要 MySQL 5.7+；如果是 5.6，把 JSON 换成 TEXT 存 JSON 字符串。
-- ============================================================================

CREATE DATABASE IF NOT EXISTS resume_helper
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_general_ci;

USE resume_helper;

-- ---------------------------------------------------------------------------
-- 岗位表
-- 对应产品方案 3.C 的字段清单：职位名称、公司名称、城市、薪资、学历、经验、
-- 技能、岗位描述、来源名称、原始链接、采集时间。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS job (
    id            VARCHAR(64)  NOT NULL COMMENT '岗位ID：由 公司+岗位+城市 归一化生成，保证跨来源去重',
    title         VARCHAR(200) NOT NULL COMMENT '职位名称',
    company       VARCHAR(200) NOT NULL COMMENT '公司名称',
    city          VARCHAR(64)           COMMENT '城市',
    district      VARCHAR(64)           COMMENT '行政区，例如 徐汇区',
    salary        VARCHAR(64)           COMMENT '薪资展示文本，例如 15-22K',
    salary_min    INT                   COMMENT '薪资下限，单位：千元/月；-1 表示面议',
    salary_max    INT                   COMMENT '薪资上限，单位：千元/月；-1 表示面议',
    education     VARCHAR(64)           COMMENT '学历要求',
    experience    VARCHAR(64)           COMMENT '经验要求原文',
    min_years     INT          DEFAULT 0 COMMENT '解析出的最低年限，用于筛选',
    job_type      VARCHAR(32)           COMMENT '全职 / 校招 / 实习',
    campus        TINYINT(1)   DEFAULT 0 COMMENT '是否校招/应届友好',
    skills        VARCHAR(512)          COMMENT '技能标签，用 | 分隔',
    description   TEXT                  COMMENT '岗位描述全文',
    source_name   VARCHAR(64)           COMMENT '来源名称，例如 演示岗位库 / CSV 岗位库 / 手动导入',
    source_url    VARCHAR(512)          COMMENT '原始链接，保证岗位可回溯',
    fingerprint   VARCHAR(255)          COMMENT '公司+岗位+城市 的归一化指纹，唯一索引用于去重',
    collected_at  DATETIME              COMMENT '采集时间',
    created_at    DATETIME     DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_job_fingerprint (fingerprint),
    KEY idx_job_city (city),
    KEY idx_job_collected_at (collected_at),
    KEY idx_job_source (source_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '岗位';

-- ---------------------------------------------------------------------------
-- 岗位匹配结果
-- 对应产品方案 7 的 JobMatch：用户与岗位的匹配原因及差距。
-- 缓存下来可以避免同一用户反复看同一岗位时重复调用大模型。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS job_match (
    user_id        VARCHAR(64)  NOT NULL COMMENT '用户标识',
    job_id         VARCHAR(64)  NOT NULL COMMENT '岗位ID',
    score          INT          NOT NULL COMMENT '匹配度 0-100。是贴合程度，不是录取概率',
    matched_skills VARCHAR(512)          COMMENT '已命中技能，用 | 分隔',
    missing_skills VARCHAR(512)          COMMENT '缺少的技能，用 | 分隔',
    reasons        TEXT                  COMMENT '为什么匹配',
    gaps           TEXT                  COMMENT '目前缺什么',
    dimensions     JSON                  COMMENT '各维度得分，例如 {"技能匹配":45,"城市":15}',
    ai_explanation VARCHAR(512)          COMMENT '大模型生成的一段解释；为空表示只用了规则',
    created_at     DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, job_id),
    KEY idx_job_match_score (score)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '岗位匹配结果';

-- ---------------------------------------------------------------------------
-- 岗位收藏（前端原型的「收藏岗位」按钮）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS job_favorite (
    user_id    VARCHAR(64) NOT NULL,
    job_id     VARCHAR(64) NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, job_id),
    KEY idx_job_favorite_created (user_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '岗位收藏';

-- ---------------------------------------------------------------------------
-- 数据源执行日志
-- 每次检索记录各来源的命中与失败情况，用于回答「为什么这次没搜到岗位」，
-- 也方便发现某个公开来源结构变了。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS job_source_log (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    source_name VARCHAR(64) NOT NULL,
    source_kind VARCHAR(32)          COMMENT 'mock / csv / public-api / public-web / search / paste',
    ok          TINYINT(1)  NOT NULL COMMENT '本次是否成功',
    hit_count   INT         DEFAULT 0,
    keyword     VARCHAR(128),
    city        VARCHAR(64),
    message     VARCHAR(512)         COMMENT '失败原因',
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
    KEY idx_source_log_time (created_at),
    KEY idx_source_log_source (source_name, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '岗位数据源执行日志';
