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

package org.apache.shenyu.plugin.global;

import org.apache.shenyu.common.constant.Constants;
import org.apache.shenyu.common.enums.PluginEnum;
import org.apache.shenyu.plugin.api.ShenyuPlugin;
import org.apache.shenyu.plugin.api.ShenyuPluginChain;
import org.apache.shenyu.plugin.api.context.ShenyuContext;
import org.apache.shenyu.plugin.api.context.ShenyuContextBuilder;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 用于做一些数据的初始化，方便后续插件责任链的调用
 * The type Global plugin.
 */
public class GlobalPlugin implements ShenyuPlugin {
    
    private final ShenyuContextBuilder builder;
    
    /**
     * Instantiates a new Global plugin.
     *
     * @param builder the builder
     */
    public GlobalPlugin(final ShenyuContextBuilder builder) {
        this.builder = builder;
    }
    
    @Override
    public Mono<Void> execute(final ServerWebExchange exchange, final ShenyuPluginChain chain) {
        // 初始化 ShenyuContext，这里会根据 exchange 携带的request，从缓存中获取对应的元数据
        // 并设置到 ShenyuContext 中方便插件责任链中后续插件的处理
        ShenyuContext shenyuContext = builder.build(exchange);
        // 将上下文设置到 exchange 的 Attribute 中
        exchange.getAttributes().put(Constants.CONTEXT, shenyuContext);
        // 继续执行插件责任链
        return chain.execute(exchange);
    }
    
    @Override
    public int getOrder() {
        return PluginEnum.GLOBAL.getCode();
    }
    
    @Override
    public String named() {
        return PluginEnum.GLOBAL.getName();
    }
}
