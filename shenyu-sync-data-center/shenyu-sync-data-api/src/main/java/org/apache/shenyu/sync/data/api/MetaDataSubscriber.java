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

package org.apache.shenyu.sync.data.api;

import org.apache.shenyu.common.dto.MetaData;

/**
 * 作为元数据订阅者的角色，将客户端上报的元数据进行处理，网关根据这些元数据才能对请求进行正确的路由和处理
 * The interface Meta data subscriber.
 */
public interface MetaDataSubscriber {
    
    /**
     * 处理订阅通知，客户端在注册时就会上报元数据
     * On subscribe.
     *
     * @param metaData the meta data
     */
    void onSubscribe(MetaData metaData);
    
    /**
     * Un subscribe.
     *
     * @param metaData the meta data
     */
    void unSubscribe(MetaData metaData);
    
    /**
     * Refresh.
     */
    default void refresh() {
    }
}
