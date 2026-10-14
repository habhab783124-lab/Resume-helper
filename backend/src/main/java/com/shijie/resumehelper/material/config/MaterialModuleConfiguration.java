package com.shijie.resumehelper.material.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shijie.resumehelper.material.parse.DocumentTextExtractor;
import com.shijie.resumehelper.material.parse.DocxTextExtractor;
import com.shijie.resumehelper.material.parse.PdfTextExtractor;
import com.shijie.resumehelper.material.parse.PlainTextExtractor;
import com.shijie.resumehelper.material.parse.TextExtractorRegistry;
import com.shijie.resumehelper.material.service.MaterialService;
import com.shijie.resumehelper.material.store.FileSystemMaterialStore;
import com.shijie.resumehelper.material.store.FileSystemProfileStore;
import com.shijie.resumehelper.material.store.MaterialStore;
import com.shijie.resumehelper.material.store.ProfileStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 材料模块的装配。
 *
 * <p>和岗位模块一样，<b>业务类不用 {@code @Component}</b>，全部在这里显式 new 出来。
 * 好处是：{@code domain} / {@code parse} / {@code extract} 这些核心类保持零框架依赖，
 * 可以脱离 Spring 直接用 javac + 一个 main 方法跑自检（{@code tools/verify.ps1} 就是靠这个）。
 */
@Configuration
@EnableConfigurationProperties(MaterialModuleProperties.class)
public class MaterialModuleConfiguration {

    // ---------------------------------------------------------------- 文本抽取

    @Bean
    public DocumentTextExtractor pdfTextExtractor(MaterialModuleProperties properties) {
        return new PdfTextExtractor(properties.getScannedTextThreshold());
    }

    @Bean
    public DocumentTextExtractor docxTextExtractor() {
        return new DocxTextExtractor();
    }

    @Bean
    public DocumentTextExtractor plainTextExtractor() {
        return new PlainTextExtractor();
    }

    /**
     * 注册表。Spring 会把上面三个 {@code DocumentTextExtractor} 都注入进来，
     * 所以以后新增格式只要再加一个 {@code @Bean}，注册表和服务层都不用改。
     */
    @Bean
    public TextExtractorRegistry textExtractorRegistry(List<DocumentTextExtractor> extractors) {
        return new TextExtractorRegistry(extractors);
    }

    // ---------------------------------------------------------------- 存储

    @Bean
    public MaterialStore materialStore(MaterialModuleProperties properties, ObjectMapper mapper) {
        return new FileSystemMaterialStore(properties, mapper);
    }

    @Bean
    public ProfileStore profileStore(MaterialModuleProperties properties, ObjectMapper mapper) {
        return new FileSystemProfileStore(properties, mapper);
    }

    // ---------------------------------------------------------------- 服务

    @Bean
    public MaterialService materialService(MaterialStore materialStore,
                                           ProfileStore profileStore,
                                           TextExtractorRegistry extractorRegistry,
                                           MaterialModuleProperties properties) {
        return new MaterialService(materialStore, profileStore, extractorRegistry, properties);
    }
}
