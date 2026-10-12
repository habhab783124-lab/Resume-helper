package com.fasterxml.jackson.databind;
public class JsonNode {
    public JsonNode path(String fieldName) { return this; }
    public JsonNode get(int index) { return this; }
    public String asText() { return ""; }
    public String asText(String defaultValue) { return defaultValue; }
    public boolean isArray() { return false; }
    public boolean isEmpty() { return true; }
    public boolean isMissingNode() { return false; }
    public boolean isNull() { return false; }
}