package com.marklogic.mule.extension.connector.internal.result.resultset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.marklogic.client.document.DocumentRecord;
import com.marklogic.client.io.BytesHandle;
import com.marklogic.client.io.DOMHandle;
import com.marklogic.client.io.JacksonHandle;
import com.marklogic.client.io.StringHandle;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Created by jkrebs on 1/19/2020.
 */
public class MarkLogicTextRecordExtractor extends MarkLogicRecordExtractor {

    // Objects used for handling text documents
    private StringHandle stringHandle = new StringHandle();

    @Override
    protected Object extractRecord(DocumentRecord record) {
        Object content;
        content = record.getContent(stringHandle).get();
        return content;
    }
}
