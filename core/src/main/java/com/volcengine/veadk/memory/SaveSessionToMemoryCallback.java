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
package com.volcengine.veadk.memory;

import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.Callbacks;
import com.google.adk.agents.InvocationContext;
import com.google.genai.types.Content;
import com.volcengine.veadk.utils.ReadonlyContextAccessorUtil;
import io.reactivex.rxjava3.core.Maybe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SaveSessionToMemoryCallback implements Callbacks.AfterAgentCallback {

    private static final Logger log = LoggerFactory.getLogger(SaveSessionToMemoryCallback.class);

    @Override
    public Maybe<Content> call(CallbackContext callbackContext) {
        InvocationContext invocationContext =
                ReadonlyContextAccessorUtil.getInvocationContext(callbackContext);
        invocationContext
                .memoryService()
                .addSessionToMemory(invocationContext.session())
                .subscribe(
                        () -> log.info("Saved session {}", invocationContext.session().id()),
                        err -> log.error("Failed to save session", err));
        return Maybe.empty();
    }
}
