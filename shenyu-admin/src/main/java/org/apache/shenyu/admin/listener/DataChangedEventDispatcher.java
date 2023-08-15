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

package org.apache.shenyu.admin.listener;

import org.apache.shenyu.admin.service.manager.LoadServiceDocEntry;
import org.apache.shenyu.common.dto.AppAuthData;
import org.apache.shenyu.common.dto.MetaData;
import org.apache.shenyu.common.dto.PluginData;
import org.apache.shenyu.common.dto.RuleData;
import org.apache.shenyu.common.dto.SelectorData;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * 数据变更事件分发器
 * Event forwarders, which forward the changed events to each ConfigEventListener.
 */
@Component
public class DataChangedEventDispatcher implements ApplicationListener<DataChangedEvent>, InitializingBean {

    private final ApplicationContext applicationContext;

    // 不同实现方式的监听器，如 Nacos、HTTP、WebSocket
    private List<DataChangedListener> listeners;

    public DataChangedEventDispatcher(final ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    /**
     * 根据事件分组将事件分发给监听器
     */
    @Override
    @SuppressWarnings("unchecked")
    public void onApplicationEvent(final DataChangedEvent event) {
        // 遍历所有实现方式的监听器
        for (DataChangedListener listener : listeners) {
            switch (event.getGroupKey()) {
                // 认证事件
                case APP_AUTH:
                    listener.onAppAuthChanged((List<AppAuthData>) event.getSource(), event.getEventType());
                    break;
                // 插件事件
                case PLUGIN:
                    listener.onPluginChanged((List<PluginData>) event.getSource(), event.getEventType());
                    break;
                // 规则事件
                case RULE:
                    listener.onRuleChanged((List<RuleData>) event.getSource(), event.getEventType());
                    break;
                // 选择器事件
                case SELECTOR:
                    // 发布选择器更新事件
                    listener.onSelectorChanged((List<SelectorData>) event.getSource(), event.getEventType());
                    applicationContext.getBean(LoadServiceDocEntry.class).loadDocOnSelectorChanged((List<SelectorData>) event.getSource(), event.getEventType());
                    break;
                // 元数据事件
                case META_DATA:
                    listener.onMetaDataChanged((List<MetaData>) event.getSource(), event.getEventType());
                    break;
                default:
                    throw new IllegalStateException("Unexpected value: " + event.getGroupKey());
            }
        }
    }

    @Override
    public void afterPropertiesSet() {
        // 注入监听器策略实现
        Collection<DataChangedListener> listenerBeans = applicationContext.getBeansOfType(DataChangedListener.class).values();
        this.listeners = Collections.unmodifiableList(new ArrayList<>(listenerBeans));
    }
}
