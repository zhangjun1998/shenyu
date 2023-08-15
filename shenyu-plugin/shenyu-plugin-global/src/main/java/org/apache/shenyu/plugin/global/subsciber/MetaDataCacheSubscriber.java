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

package org.apache.shenyu.plugin.global.subsciber;

import org.apache.shenyu.plugin.base.cache.MetaDataCache;
import org.apache.shenyu.common.dto.MetaData;
import org.apache.shenyu.sync.data.api.MetaDataSubscriber;

/**
 * 元数据缓存
 *
 * The type Meta data cache subscriber.
 */
public class MetaDataCacheSubscriber implements MetaDataSubscriber {

    /**
     * 客户端在进行注册时，会将自身元数据注册到网关，其中包括appName，contextPath，rpcType，serviceName，methodName，parameterTypes等
     * 网关根据注册的这些元数据就可以在插件责任链中判断插件是否需要处理/跳过该请求，并进行后续处理
     */
    @Override
    public void onSubscribe(final MetaData metaData) {
        // 初始化元数据缓存
        MetaDataCache.getInstance().cache(metaData);
    }
    
    @Override
    public void unSubscribe(final MetaData metaData) {
        MetaDataCache.getInstance().remove(metaData);
    }
}
