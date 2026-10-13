/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.infra.chat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BaiLianChatClientTest {

    private OkHttpClient httpClient;
    private Call call;
    private BaiLianChatClient client;
    private ModelTarget target;

    @BeforeEach
    void setUp() {
        httpClient = mock(OkHttpClient.class);
        call = mock(Call.class);
        when(httpClient.newCall(any(Request.class))).thenReturn(call);
        client = new BaiLianChatClient(httpClient, Runnable::run);

        var provider = new AIModelProperties.ProviderConfig();
        provider.setUrl("https://example.invalid");
        provider.setApiKey("test-key");
        provider.setEndpoints(Map.of("chat", "/compatible-mode/v1/chat/completions"));
        var candidate = new AIModelProperties.ModelCandidate();
        candidate.setModel("qwen3.8-max");
        candidate.setSupportsThinking(true);
        target = new ModelTarget("qwen3.8-max", candidate, provider);
    }

    static Stream<Arguments> thinkingModes() {
        return Stream.of(
                Arguments.of(null, false),
                Arguments.of(false, false),
                Arguments.of(true, true)
        );
    }

    @ParameterizedTest
    @MethodSource("thinkingModes")
    void synchronousRequestExplicitlyControlsThinking(Boolean thinking, boolean expected) throws Exception {
        stubResponse("{\"choices\":[{\"message\":{\"content\":\"OK\"}}]}", "application/json");

        assertEquals("OK", client.chat(request(thinking), target));

        JsonObject body = capturedBody();
        assertTrue(body.has("enable_thinking"));
        assertEquals(expected, body.get("enable_thinking").getAsBoolean());
        assertEquals("qwen3.8-max", body.get("model").getAsString());
        assertFalse(body.has("stream"));
    }

    @ParameterizedTest
    @MethodSource("thinkingModes")
    void streamRequestControlsThinkingAndDeliversContent(Boolean thinking, boolean expected) throws Exception {
        stubResponse("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"thinking\",\"content\":\"OK\"}}]}\n\n"
                + "data: [DONE]\n\n", "text/event-stream");
        StreamCallback callback = mock(StreamCallback.class);

        client.streamChat(request(thinking), callback, target);

        JsonObject body = capturedBody();
        assertEquals(expected, body.get("enable_thinking").getAsBoolean());
        assertTrue(body.get("stream").getAsBoolean());
        verify(callback).onContent("OK");
        verify(callback).onComplete();
        verify(callback, never()).onError(any());
        if (expected) {
            verify(callback).onThinking("thinking");
        } else {
            verify(callback, never()).onThinking(any());
        }
    }

    @Test
    void nonThinkingModelDoesNotReceiveThinkingParameter() throws Exception {
        target.candidate().setSupportsThinking(false);
        stubResponse("{\"choices\":[{\"message\":{\"content\":\"OK\"}}]}", "application/json");

        client.chat(request(false), target);

        assertFalse(capturedBody().has("enable_thinking"));
    }

    private ChatRequest request(Boolean thinking) {
        return ChatRequest.builder()
                .messages(List.of(ChatMessage.user("Reply with OK.")))
                .thinking(thinking)
                .build();
    }

    private void stubResponse(String body, String contentType) throws Exception {
        when(call.execute()).thenReturn(new Response.Builder()
                .request(new Request.Builder().url("https://example.invalid").build())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ResponseBody.create(body, MediaType.get(contentType)))
                .build());
    }

    private JsonObject capturedBody() throws Exception {
        ArgumentCaptor<Request> captor = ArgumentCaptor.forClass(Request.class);
        verify(httpClient).newCall(captor.capture());
        Buffer buffer = new Buffer();
        captor.getValue().body().writeTo(buffer);
        return JsonParser.parseString(buffer.readUtf8()).getAsJsonObject();
    }
}
