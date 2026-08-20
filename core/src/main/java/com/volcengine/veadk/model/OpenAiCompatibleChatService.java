/**
 * Copyright (c) 2025 Beijing Volcano Engine Technology Co., Ltd. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.volcengine.veadk.model;

import com.volcengine.ark.runtime.model.completion.chat.ChatCompletionChunk;
import com.volcengine.ark.runtime.model.completion.chat.ChatCompletionRequest;
import com.volcengine.ark.runtime.model.completion.chat.ChatCompletionResult;
import com.volcengine.ark.runtime.service.ArkBaseService;
import com.volcengine.ark.runtime.service.ArkService;
import io.reactivex.Single;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Retrofit;
import retrofit2.http.Body;
import retrofit2.http.POST;
import retrofit2.http.Streaming;

/**
 * Chat Completions transport for OpenAI-compatible API bases.
 *
 * <p>The Ark Java SDK version used by VeADK hard-codes {@code /api/v3/chat/completions}. AgentKit
 * ModelCenter, like the OpenAI client used by VeADK Python, expects the configured API base to be
 * combined with the relative {@code chat/completions} resource path.
 */
final class OpenAiCompatibleChatService {

    private final ChatApi api;

    OpenAiCompatibleChatService(String apiBase, String apiKey) {
        Retrofit retrofit =
                ArkService.defaultRetrofit(
                        ArkService.defaultApiKeyClient(apiKey, ArkBaseService.DEFAULT_TIMEOUT),
                        ArkService.defaultObjectMapper(),
                        normalizeApiBase(apiBase),
                        null);
        this.api = retrofit.create(ChatApi.class);
    }

    ChatCompletionResult createChatCompletion(ChatCompletionRequest request) {
        return ArkService.execute(api.createChatCompletion(request));
    }

    io.reactivex.Flowable<ChatCompletionChunk> streamChatCompletion(ChatCompletionRequest request) {
        request.setStream(true);
        return ArkService.stream(
                api.createChatCompletionStream(request), ChatCompletionChunk.class);
    }

    static String normalizeApiBase(String apiBase) {
        String normalized = apiBase.trim();
        return normalized.endsWith("/") ? normalized : normalized + "/";
    }

    private interface ChatApi {

        @POST("chat/completions")
        Single<ChatCompletionResult> createChatCompletion(@Body ChatCompletionRequest request);

        @Streaming
        @POST("chat/completions")
        Call<ResponseBody> createChatCompletionStream(@Body ChatCompletionRequest request);
    }
}
