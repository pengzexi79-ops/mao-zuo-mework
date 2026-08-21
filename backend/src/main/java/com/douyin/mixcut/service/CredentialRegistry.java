package com.douyin.mixcut.service;

import com.douyin.mixcut.config.AppProps;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Reads the API credential declarations bundled with capabilities.json. Only declared config IDs
 * can be saved by the local configuration API; browser requests can never select an env variable.
 */
@Service
public class CredentialRegistry {
    private static final Pattern KEY_VALUE = Pattern.compile("^[A-Za-z0-9_./:+-]+$");
    private final Map<String, Credential> byConfigId;
    private final Map<String, Credential> byProvider;
    private final Map<String, Credential> byCapability;

    public CredentialRegistry() {
        Map<String, Credential> configs = new LinkedHashMap<>();
        Map<String, Credential> providers = new LinkedHashMap<>();
        Map<String, Credential> capabilities = new LinkedHashMap<>();
        try (var input = new ClassPathResource("capabilities.json").getInputStream()) {
            JsonNode root = new ObjectMapper().readTree(input);
            for (JsonNode node : root.path("capabilities")) {
                JsonNode credential = node.path("credential");
                if (!credential.isObject()) continue;
                Credential entry = new Credential(
                        credential.path("configId").asText(""),
                        node.path("key").asText(""),
                        credential.path("provider").asText(""),
                        credential.path("propertyName").asText(""),
                        credential.path("environmentVariable").asText(""),
                        credential.path("mediaType").asText(""),
                        credential.path("testQuery").asText(""),
                        credential.path("minLength").asInt(12),
                        credential.path("maxLength").asInt(512),
                        credential.path("restartRequired").asBoolean(true));
                validateDeclaration(entry);
                if (configs.putIfAbsent(entry.configId(), entry) != null
                        || providers.putIfAbsent(entry.provider(), entry) != null
                        || capabilities.putIfAbsent(entry.capabilityKey(), entry) != null) {
                    throw new IllegalStateException("能力清单存在重复凭据配置：" + entry.configId());
                }
            }
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("无法加载凭据能力清单：" + e.getMessage(), e);
        }
        this.byConfigId = Map.copyOf(configs);
        this.byProvider = Map.copyOf(providers);
        this.byCapability = Map.copyOf(capabilities);
    }

    public Optional<Credential> byConfigId(String configId) {
        return Optional.ofNullable(byConfigId.get(normalize(configId)));
    }

    /** Legacy provider names are accepted only when they resolve to a declared config entry. */
    public Optional<Credential> byProvider(String provider) {
        return Optional.ofNullable(byProvider.get(normalize(provider)));
    }

    public Optional<Credential> byCapabilityKey(String capabilityKey) {
        return Optional.ofNullable(byCapability.get(normalize(capabilityKey)));
    }

    public List<Credential> all() {
        return new ArrayList<>(byConfigId.values());
    }

    public boolean configured(Credential credential, AppProps props) {
        String value = currentValue(credential, props);
        return value != null && !value.isBlank();
    }

    public String currentValue(Credential credential, AppProps props) {
        Object value = new BeanWrapperImpl(props).getPropertyValue(credential.propertyName());
        return value instanceof String text ? text : "";
    }

    public void validateValue(Credential credential, String rawValue) {
        String value = rawValue == null ? "" : rawValue.trim();
        if (value.length() < credential.minLength() || value.length() > credential.maxLength()
                || !KEY_VALUE.matcher(value).matches()) {
            throw new IllegalArgumentException("API Key 格式不正确。请粘贴官方控制台生成的完整密钥；该值不会在页面再次显示。");
        }
    }

    public String mask(String value) {
        if (value == null || value.length() < 8) return "已配置";
        return value.substring(0, 3) + "****" + value.substring(value.length() - 3);
    }

    public Map<String, Object> status(Credential credential, AppProps props) {
        boolean configured = configured(credential, props);
        Map<String, Object> status = metadata(credential);
        status.put("configured", configured);
        status.put("masked", configured ? mask(currentValue(credential, props)) : "");
        return status;
    }

    public Map<String, Object> metadata(Credential credential) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("configId", credential.configId());
        metadata.put("provider", credential.provider());
        metadata.put("variable", credential.environmentVariable());
        metadata.put("mediaType", credential.mediaType());
        metadata.put("restartRequired", credential.restartRequired());
        return metadata;
    }

    private static void validateDeclaration(Credential credential) {
        if (credential.configId().isBlank() || credential.capabilityKey().isBlank() || credential.provider().isBlank()
                || credential.propertyName().isBlank() || credential.environmentVariable().isBlank() || credential.mediaType().isBlank()
                || credential.testQuery().isBlank() || credential.minLength() < 8
                || credential.maxLength() < credential.minLength()) {
            throw new IllegalStateException("能力清单凭据声明缺少必填字段：" + credential.capabilityKey());
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public record Credential(String configId, String capabilityKey, String provider, String propertyName,
                             String environmentVariable, String mediaType, String testQuery,
                             int minLength, int maxLength, boolean restartRequired) { }
}
