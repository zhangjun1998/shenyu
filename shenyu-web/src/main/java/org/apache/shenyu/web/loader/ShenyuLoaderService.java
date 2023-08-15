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

package org.apache.shenyu.web.loader;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.shenyu.common.concurrent.ShenyuThreadFactory;
import org.apache.shenyu.common.config.ShenyuConfig;
import org.apache.shenyu.common.config.ShenyuConfig.ExtPlugin;
import org.apache.shenyu.plugin.api.ShenyuPlugin;
import org.apache.shenyu.plugin.base.cache.CommonPluginDataSubscriber;
import org.apache.shenyu.plugin.base.handler.PluginDataHandler;
import org.apache.shenyu.web.handler.ShenyuWebHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 类加载服务
 * The type Shenyu loader service.
 */
public class ShenyuLoaderService {
    
    private static final Logger LOG = LoggerFactory.getLogger(ShenyuLoaderService.class);
    
    private final ShenyuWebHandler webHandler;
    
    private final CommonPluginDataSubscriber subscriber;
    
    private final ShenyuConfig shenyuConfig;
    
    /**
     * Instantiates a new Shenyu loader service.
     *
     * @param webHandler   the web handler
     * @param subscriber   the subscriber
     * @param shenyuConfig the shenyu config
     */
    public ShenyuLoaderService(final ShenyuWebHandler webHandler, final CommonPluginDataSubscriber subscriber, final ShenyuConfig shenyuConfig) {
        this.subscriber = subscriber;
        this.webHandler = webHandler;
        this.shenyuConfig = shenyuConfig;
        ExtPlugin config = shenyuConfig.getExtPlugin();
        if (config.getEnabled()) {
            // 线程池，默认单线程定时扫描加载指定路径下的插件
            ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(config.getThreads(), ShenyuThreadFactory.create("plugin-ext-loader", true));
            executor.scheduleAtFixedRate(this::loaderExtPlugins, config.getScheduleDelay(), config.getScheduleTime(), TimeUnit.SECONDS);
        }
    }

    /**
     * 加载配置路径下的插件
     */
    private void loaderExtPlugins() {
        try {
            // 加载扩展插件并获取加载结果
            List<ShenyuLoaderResult> results = ShenyuPluginLoader.getInstance().loadExtendPlugins(shenyuConfig.getExtPlugin().getPath());
            if (CollectionUtils.isEmpty(results)) {
                return;
            }
            // 加载的扩展插件列表
            List<ShenyuPlugin> shenyuExtendPlugins = results.stream().map(ShenyuLoaderResult::getShenyuPlugin).filter(Objects::nonNull).collect(Collectors.toList());
            // 将扩展插件添加到 ShenyuWebHandler 中
            webHandler.putExtPlugins(shenyuExtendPlugins);
            // 加载的扩展插件处理器
            List<PluginDataHandler> handlers = results.stream().map(ShenyuLoaderResult::getPluginDataHandler).filter(Objects::nonNull).collect(Collectors.toList());
            // 将扩展插件处理器添加到 CommonPluginDataSubscriber 中
            subscriber.putExtendPluginDataHandler(handlers);
        } catch (Exception e) {
            LOG.error("shenyu ext plugins load has error ", e);
        }
    }
}
