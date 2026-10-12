package com.fasterxml.jackson.databind.node;
import com.fasterxml.jackson.databind.JsonNode;
public class ObjectNode extends JsonNode {
    public ObjectNode put(String fieldName, String value) { return this; }
    public ObjectNode put(String fieldName, int value) { return this; }
    public ObjectNode put(String fieldName, double value) { return this; }
    public ObjectNode put(String fieldName, boolean value) { return this; }
    public ArrayNode putArray(String fieldName) { return new ArrayNode(); }
    @Override public String toString() { return "{}"; }
}