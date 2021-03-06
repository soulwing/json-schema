package org.everit.json.schema;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.everit.json.schema.regexp.Regexp;
import org.everit.json.schema.spi.JsonAdaptation;
import org.everit.json.schema.spi.JsonObjectAdapter;

class ObjectSchemaValidatingVisitor extends Visitor {

    private final Object subject;

    private JsonObjectAdapter objSubject;

    private ObjectSchema schema;

    private int objectSize;

    private final ValidatingVisitor owner;

    private final JsonAdaptation<?> jsonAdaptation;

    public ObjectSchemaValidatingVisitor(Object subject, ValidatingVisitor owner, JsonAdaptation<?> jsonAdaptation) {
        this.subject = requireNonNull(subject, "subject cannot be null");
        this.owner = requireNonNull(owner, "owner cannot be null");
        this.jsonAdaptation = jsonAdaptation;
    }

    @Override void visitObjectSchema(ObjectSchema objectSchema) {
        if (owner.passesTypeCheck(jsonAdaptation.objectType(), objectSchema.requiresObject(),
                objectSchema.isNullable())) {
            objSubject = (JsonObjectAdapter) jsonAdaptation.adapt(subject);
            objectSize = objSubject.length();
            this.schema = objectSchema;
            super.visitObjectSchema(objectSchema);
        }
    }

    @Override void visitRequiredPropertyName(String requiredPropName) {
        if (!objSubject.has(requiredPropName)) {
            owner.failure(format("required key [%s] not found", requiredPropName), "required");
        }
    }

    @Override void visitPropertyNameSchema(Schema propertyNameSchema) {
        if (propertyNameSchema != null) {
            String[] names = objSubject.keys();
            if (names == null || names.length == 0) {
                return;
            }
            for (String name : names) {
                ValidationException failure = owner.getFailureOfSchema(propertyNameSchema, name);
                if (failure != null) {
                    owner.failure(failure.prepend(name));
                }
            }
        }
    }

    @Override void visitMinProperties(Integer minProperties) {
        if (minProperties != null && objectSize < minProperties.intValue()) {
            owner.failure(format("minimum size: [%d], found: [%d]", minProperties, objectSize), "minProperties");
        }
    }

    @Override void visitMaxProperties(Integer maxProperties) {
        if (maxProperties != null && objectSize > maxProperties.intValue()) {
            owner.failure(format("maximum size: [%d], found: [%d]", maxProperties, objectSize), "maxProperties");
        }
    }

    @Override void visitPropertyDependencies(String ifPresent, Set<String> allMustBePresent) {
        if (objSubject.has(ifPresent)) {
            for (String mustBePresent : allMustBePresent) {
                if (!objSubject.has(mustBePresent)) {
                    owner.failure(format("property [%s] is required", mustBePresent), "dependencies");
                }
            }
        }
    }

    @Override void visitAdditionalProperties(boolean permitsAdditionalProperties) {
        if (!permitsAdditionalProperties) {
            List<String> additionalProperties = getAdditionalProperties();
            if (null == additionalProperties || additionalProperties.isEmpty()) {
                return;
            }
            for (String additionalProperty : additionalProperties) {
                owner.failure(format("extraneous key [%s] is not permitted", additionalProperty), "additionalProperties");
            }
        }
    }

    @Override void visitSchemaOfAdditionalProperties(Schema schemaOfAdditionalProperties) {
        if (schemaOfAdditionalProperties != null) {
            List<String> additionalPropNames = getAdditionalProperties();
            for (String propName : additionalPropNames) {
                Object propVal = objSubject.get(propName);
                ValidationException failure = owner.getFailureOfSchema(schemaOfAdditionalProperties, propVal);
                if (failure != null) {
                    owner.failure(failure.prepend(propName, schema));
                }
            }
        }
    }

    private List<String> getAdditionalProperties() {
        String[] names = objSubject.keys();
        if (names == null) {
            return new ArrayList<>();
        } else {
            List<String> namesList = new ArrayList<>();
            for (String name : names) {
                if (!schema.getPropertySchemas().containsKey(name) && !matchesAnyPattern(name)) {
                    namesList.add(name);
                }
            }
            return namesList;
        }
    }

    private boolean matchesAnyPattern(String key) {
        for (Regexp pattern : schema.getRegexpPatternProperties().keySet()) {
            if (!pattern.patternMatchingFailure(key).isPresent()) {
                return true;
            }
        }
        return false;
    }

    @Override void visitPatternPropertySchema(Regexp propertyNamePattern, Schema schema) {
        String[] propNames = objSubject.keys();
        if (propNames == null || propNames.length == 0) {
            return;
        }
        for (String propName : propNames) {
            if (!propertyNamePattern.patternMatchingFailure(propName).isPresent()) {
                ValidationException failure = owner.getFailureOfSchema(schema, objSubject.get(propName));
                if (failure != null) {
                    owner.failure(failure.prepend(propName));
                }
            }
        }
    }

    @Override void visitSchemaDependency(String propName, Schema schema) {
        if (objSubject.has(propName)) {
            ValidationException failure = owner.getFailureOfSchema(schema, objSubject);
            if (failure != null) {
                owner.failure(failure);
            }
        }
    }

    @SuppressWarnings("unchecked")
    @Override void visitPropertySchema(String propertyName, Schema schema) {
        if (objSubject.has(propertyName)) {
            ValidationException failure = owner.getFailureOfSchema(schema, objSubject.get(propertyName));
            if (failure != null) {
                owner.failure(failure.prepend(propertyName));
            }
        } else if (schema.hasDefaultValue()) {
            // we're using the raw type here under the assumption that the object adapter and
            // the adaptation that produced it are using the same type T.
            objSubject.put(propertyName, jsonAdaptation.invert(schema.getDefaultValue()));
        }
    }
}
