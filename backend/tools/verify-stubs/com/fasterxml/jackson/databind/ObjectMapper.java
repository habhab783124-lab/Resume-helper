package com.fasterxml.jackson.databind;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;
public class ObjectMapper {
    public ObjectNode createObjectNode() { return new ObjectNode(); }
    public JsonNode readTree(String content) throws JsonProcessingException { return new JsonNode(); }
}