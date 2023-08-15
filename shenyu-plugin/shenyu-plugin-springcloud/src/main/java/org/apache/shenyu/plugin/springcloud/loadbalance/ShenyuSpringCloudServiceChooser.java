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

package org.apache.shenyu.plugin.springcloud.loadbalance;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.shenyu.common.dto.convert.selector.SpringCloudSelectorHandle;
import org.apache.shenyu.loadbalancer.cache.UpstreamCacheManager;
import org.apache.shenyu.loadbalancer.entity.Upstream;
import org.apache.shenyu.loadbalancer.factory.LoadBalancerFactory;
import org.apache.shenyu.plugin.springcloud.handler.SpringCloudPluginDataHandler;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * spring cloud plugin loadbalancer.
 */
public final class ShenyuSpringCloudServiceChooser {

    // 注册中心客户端，由SpringCloud微服务规范定义，具体实现由引用的依赖决定，支持Eureka、Nacos等
    private final DiscoveryClient discoveryClient;

    public ShenyuSpringCloudServiceChooser(final DiscoveryClient discoveryClient) {
        this.discoveryClient = discoveryClient;
    }

    /**
     * 选择服务实例
     * choose service instance.
     *
     * @param serviceId service id
     * @param selectorId selector id
     * @param ip ip
     * @param loadbalancer load balancer
     * @return Upstream
     */
    public Upstream choose(final String serviceId, final String selectorId,
                           final String ip, final String loadbalancer) {
        // load service instance by serviceId 根据服务id获取所有可用的实例
        List<ServiceInstance> available = this.getServiceInstance(serviceId);
        if (CollectionUtils.isEmpty(available)) {
            return null;
        }
        // 从缓存中获取选择器信息
        final SpringCloudSelectorHandle springCloudSelectorHandle = SpringCloudPluginDataHandler.SELECTOR_CACHED.get().obtainHandle(selectorId);
        // not gray flow 非灰度调度
        if (!springCloudSelectorHandle.getGray()) {
            // load service from register center 根据选择器规则指定的负载均衡策略从注册中心选择合适的服务实例
            return this.doSelect(serviceId, ip, loadbalancer);
        }
        // 灰度调度，灰度流程会走选择器配置中的特定实例
        List<Upstream> divideUpstreams = UpstreamCacheManager.getInstance().findUpstreamListBySelectorId(selectorId);
        // gray flow,but upstream is null
        if (CollectionUtils.isEmpty(divideUpstreams)) {
            return this.doSelect(serviceId, ip, loadbalancer);
        }
        // select server from available to choose
        final List<Upstream> choose = new ArrayList<>(available.size());
        for (ServiceInstance serviceInstance : available) {
            divideUpstreams.stream()
                    .filter(Upstream::isStatus)
                    .filter(upstream -> Objects.equals(upstream.getUrl(), serviceInstance.getUri().getRawAuthority()))
                    .findFirst().ifPresent(choose::add);
        }
        if (CollectionUtils.isEmpty(choose)) {
            return this.doSelect(serviceId, ip, loadbalancer);
        }
        // select by divideUpstreams
        return this.doSelect(choose, loadbalancer, ip);
    }

    /**
     * 使用负载均衡器从注册中心中选择合适的服务实例
     * select serviceInstance by shenyu loadbalancer from register center.
     *
     * @param serviceId serviceId
     * @return ServiceInstance
     */
    private Upstream doSelect(final String serviceId, final String ip, final String loadbalancer) {
        // 从注册中心拉取实例列表，并转换为Upstream对象方便后续调用
        List<Upstream> choose = this.buildUpstream(serviceId);
        // 根据负载均衡策略选择合适的实例
        return this.doSelect(choose, loadbalancer, ip);
    }

    /**
     * execute loadbalancer by shenyu loadbalancer.
     *
     * @param upstreamList upstream list
     * @return ServiceInstance
     */
    private Upstream doSelect(final List<Upstream> upstreamList, final String loadbalancer, final String ip) {
        return LoadBalancerFactory.selector(upstreamList, loadbalancer, ip);
    }

    /**
     * 根据服务id获取所有可用的实例
     * get service instance by serviceId.
     *
     * @param serviceId serviceId
     * @return {@linkplain ServiceInstance}
     */
    private List<ServiceInstance> getServiceInstance(final String serviceId) {
        List<String> serviceNames = discoveryClient.getServices().stream().map(String::toUpperCase).collect(Collectors.toList());
        if (!serviceNames.contains(serviceId.toUpperCase())) {
            return Collections.emptyList();
        }
        return discoveryClient.getInstances(serviceId);
    }

    /**
     * build upstream by service instance.
     *
     * @param serviceId serviceId
     * @return Upstream List
     */
    private List<Upstream> buildUpstream(final String serviceId) {
        List<ServiceInstance> serviceInstanceList = this.getServiceInstance(serviceId);
        if (serviceInstanceList.isEmpty()) {
            return Collections.emptyList();
        }
        return serviceInstanceList.stream()
                .map(serviceInstance -> buildDefaultSpringCloudUpstream(serviceInstance.getUri().getRawAuthority(),
                        serviceInstance.getScheme() + "://"))
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * build default spring cloud upstream.
     *
     * @param upstreamUrl url
     * @param protocol protocol
     * @return Upstream
     */
    private static Upstream buildDefaultSpringCloudUpstream(final String upstreamUrl, final String protocol) {
        return Upstream.builder().url(upstreamUrl)
                .protocol(protocol)
                .weight(50)
                .warmup(10)
                .timestamp(0)
                .build();
    }
}
