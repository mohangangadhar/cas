package org.apereo.cas.authentication.attribute;

import org.apereo.cas.util.LoggingUtils;
import org.apereo.cas.util.ResourceUtils;
import org.apereo.cas.util.function.FunctionUtils;
import org.apereo.cas.util.io.FileWatcherService;
import org.apereo.cas.util.serialization.JacksonObjectMapperFactory;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.hjson.JsonValue;
import org.jooq.lambda.Unchecked;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * This is {@link DefaultAttributeDefinitionStore}.
 *
 * @author Misagh Moayyed
 * @author Travis Schmidt
 * @since 6.2.0
 */
@Slf4j
@EqualsAndHashCode(of = "attributeDefinitions")
@ToString(of = "attributeDefinitions")
public class DefaultAttributeDefinitionStore implements AttributeDefinitionStore, DisposableBean, AutoCloseable {
    private static final ObjectMapper MAPPER = JacksonObjectMapperFactory.builder()
        .defaultTypingEnabled(true).build().toObjectMapper();

    private final Map<String, AttributeDefinition> attributeDefinitions = new TreeMap<>();

    private FileWatcherService storeWatcherService;

    @Setter
    @Getter
    private String scope = StringUtils.EMPTY;

    public DefaultAttributeDefinitionStore(final Resource resource) throws Exception {
        if (ResourceUtils.doesResourceExist(resource)) {
            loadAttributeDefinitionsFromInputStream(resource);

            if (ResourceUtils.isFile(resource)) {
                this.storeWatcherService = new FileWatcherService(resource.getFile(),
                    Unchecked.consumer(file -> loadAttributeDefinitionsFromInputStream(new FileSystemResource(file))));
                this.storeWatcherService.start(getClass().getSimpleName());
            }
        }
    }

    public DefaultAttributeDefinitionStore(final AttributeDefinition... definitions) {
        Arrays.stream(definitions).forEach(this::registerAttributeDefinition);
    }

    private static String getAttributeDefinitionKey(final String key, final AttributeDefinition definition) {
        if (StringUtils.isNotBlank(definition.getKey()) && !StringUtils.equalsIgnoreCase(definition.getKey(), key)) {
            LOGGER.warn("Attribute definition contains a key property [{}] that differs from its registering key [{}]. "
                + "This is likely due to misconfiguration of the attribute definition, and CAS will use the key property [{}] "
                + "to register the attribute definition in the attribute store", definition.getKey(), key, definition.getKey());
            return definition.getKey();
        }
        return key;
    }

    @Override
    @CanIgnoreReturnValue
    public AttributeDefinitionStore registerAttributeDefinition(final AttributeDefinition definition) {
        return registerAttributeDefinition(definition.getKey(), definition);
    }

    @Override
    @CanIgnoreReturnValue
    public AttributeDefinitionStore registerAttributeDefinition(final String key, final AttributeDefinition definition) {
        LOGGER.trace("Registering attribute definition [{}] by key [{}]", definition, key);
        val keyToUse = getAttributeDefinitionKey(key, definition);
        attributeDefinitions.putIfAbsent(keyToUse, definition);
        return this;
    }

    @Override
    @CanIgnoreReturnValue
    public AttributeDefinitionStore removeAttributeDefinition(final String key) {
        LOGGER.debug("Removing attribute definition by key [{}]", key);

        if (this.attributeDefinitions.containsKey(key)) {
            val definition = this.attributeDefinitions.remove(key);
            LOGGER.debug("Attribute definition [{}] has been removed from the definition store", definition);
        } else {
            LOGGER.debug("Attribute definition with the registered key [{}] was not found and the store was not altered", key);
        }
        return this;
    }

    @Override
    public Optional<AttributeDefinition> locateAttributeDefinitionByName(final String name) {
        return attributeDefinitions
            .values()
            .stream()
            .filter(entry -> StringUtils.isNotBlank(entry.getName()))
            .filter(entry -> entry.getName().equalsIgnoreCase(name))
            .findFirst();
    }

    @Override
    public Optional<AttributeDefinition> locateAttributeDefinition(final String key) {
        LOGGER.trace("Locating attribute definition for [{}]", key);
        return Optional.ofNullable(attributeDefinitions.get(key));
    }

    @Override
    public <T extends AttributeDefinition> Optional<T> locateAttributeDefinition(final String key, final Class<T> clazz) {
        LOGGER.trace("Locating attribute definition for [{}]", key);
        val attributeDefinition = attributeDefinitions.get(key);
        if (attributeDefinition != null && clazz.isAssignableFrom(attributeDefinition.getClass())) {
            return Optional.of((T) attributeDefinition);
        }
        return Optional.empty();
    }

    @Override
    public <T extends AttributeDefinition> Optional<T> locateAttributeDefinition(final Predicate<AttributeDefinition> predicate) {
        return attributeDefinitions.values()
            .stream()
            .filter(predicate)
            .map(definition -> (T) definition)
            .findFirst();
    }

    @Override
    public Collection<AttributeDefinition> getAttributeDefinitions() {
        return attributeDefinitions.values();
    }

    @Override
    public <T extends AttributeDefinition> Stream<T> getAttributeDefinitionsBy(final Class<T> type) {
        return attributeDefinitions
            .values()
            .stream()
            .filter(definition -> type.isAssignableFrom(definition.getClass()))
            .map(type::cast);
    }

    @Override
    public Optional<Pair<AttributeDefinition, List<Object>>> resolveAttributeValues(
        final String key,
        final AttributeDefinitionResolutionContext context) {
        val result = locateAttributeDefinition(key);
        return result.flatMap(definition -> FunctionUtils.doUnchecked(() -> {
            val currentValues = definition.resolveAttributeValues(context.withScope(this.scope));
            return Optional.of(Pair.of(definition, currentValues));
        }));
    }

    @Override
    public boolean isEmpty() {
        return attributeDefinitions.isEmpty();
    }

    @Override
    @CanIgnoreReturnValue
    public AttributeDefinitionStore store(final Resource resource) {
        return FunctionUtils.doUnchecked(() -> {
            val json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(this.attributeDefinitions);
            LOGGER.trace("Storing attribute definitions as [{}] to [{}]", json, resource);
            try (val writer = Files.newBufferedWriter(resource.getFile().toPath(), StandardCharsets.UTF_8)) {
                writer.write(json);
                writer.flush();
            }
            return this;
        });
    }

    @Override
    @CanIgnoreReturnValue
    public AttributeDefinitionStore importStore(final AttributeDefinitionStore definitionStore) {
        definitionStore.getAttributeDefinitions().forEach(this::registerAttributeDefinition);
        return this;
    }

    @Override
    public void close() {
        if (this.storeWatcherService != null) {
            this.storeWatcherService.close();
        }
    }

    @Override
    public void destroy() {
        close();
    }

    private void loadAttributeDefinitionsFromInputStream(final Resource resource) {
        try {
            LOGGER.trace("Loading attribute definitions from [{}]", resource);
            val json = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            LOGGER.trace("Loaded attribute definitions [{}] from [{}]", json, resource);
            val map = MAPPER.readValue(JsonValue.readHjson(json).toString(), new TypeReference<Map<String, AttributeDefinition>>() {
            });
            map.forEach(this::registerAttributeDefinition);
        } catch (final Exception e) {
            LoggingUtils.warn(LOGGER, e);
        } finally {
            LOGGER.debug("Loaded [{}] attribute definition(s).", attributeDefinitions.size());
        }
    }
}
