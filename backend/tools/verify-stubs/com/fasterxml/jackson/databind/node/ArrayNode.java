package com.fasterxml.jackson.databind.node;
import com.fasterxml.jackson.databind.JsonNode;
public class ArrayNode extends JsonNode {
    public ObjectNode addObject() { return new ObjectNode(); }
}