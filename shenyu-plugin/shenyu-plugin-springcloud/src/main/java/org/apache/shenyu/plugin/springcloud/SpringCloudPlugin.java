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

package org.apache.shenyu.plugin.springcloud;

import org.apache.commons.lang3.StringUtils;
import org.apache.shenyu.common.constant.Constants;
import org.apache.shenyu.common.dto.RuleData;
import org.apache.shenyu.common.dto.SelectorData;
import org.apache.shenyu.common.dto.convert.rule.impl.SpringCloudRuleHandle;
import org.apache.shenyu.common.dto.convert.selector.SpringCloudSelectorHandle;
import org.apache.shenyu.common.enums.PluginEnum;
import org.apache.shenyu.common.enums.RpcTypeEnum;
import org.apache.shenyu.loadbalancer.entity.Upstream;
import org.apache.shenyu.plugin.api.ShenyuPluginChain;
import org.apache.shenyu.plugin.api.context.ShenyuContext;
import org.apache.shenyu.plugin.api.result.ShenyuResultEnum;
import org.apache.shenyu.plugin.api.result.ShenyuResultWrap;
import org.apache.shenyu.plugin.api.utils.WebFluxResultUtils;
import org.apache.shenyu.plugin.base.AbstractShenyuPlugin;
import org.apache.shenyu.plugin.base.utils.CacheKeyUtils;
import org.apache.shenyu.plugin.springcloud.handler.SpringCloudPluginDataHandler;
import org.apache.shenyu.plugin.springcloud.loadbalance.ShenyuSpringCloudServiceChooser;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.Objects;

/**
 * this is springCloud proxy impl.
 *
 * 核心逻辑：将网关和服务都加入到注册中心，这样网关就可以通过注册中心拉取服务实例
 * 再根据选择器配置选择合适的负载均衡策略即可获得需要调用的实例，根据实例注册的元数据组装 domain+path 发起调用即可
 */
public class SpringCloudPlugin extends AbstractShenyuPlugin {

    // 服务实例选择器
    private final ShenyuSpringCloudServiceChooser serviceChooser;
    
    private final SpringCloudRuleHandle defaultRuleHandle = new SpringCloudRuleHandle();

    /**
     * Instantiates a new Spring cloud plugin.
     *
     * @param serviceInstanceChooser the load balancer
     */
    public SpringCloudPlugin(final ShenyuSpringCloudServiceChooser serviceInstanceChooser) {
        this.serviceChooser = serviceInstanceChooser;
    }

    @Override
    protected Mono<Void> doExecute(final ServerWebExchange exchange, final ShenyuPluginChain chain,
                                   final SelectorData selector, final RuleData rule) {
        if (Objects.isNull(rule)) {
            return Mono.empty();
        }
        final ShenyuContext shenyuContext = exchange.getAttribute(Constants.CONTEXT);
        assert shenyuContext != null;
        // 选择器的一些元数据，在服务启动时会自动注册选择器，这时会将选择器元数据上传并写入缓存，后续选择器相关的事件也会更新缓存
        final SpringCloudSelectorHandle springCloudSelectorHandle = SpringCloudPluginDataHandler.SELECTOR_CACHED.get().obtainHandle(selector.getId());
        // 选择器的具体规则
        final SpringCloudRuleHandle ruleHandle = buildRuleHandle(rule);
        // 从选择器中获取服务id
        String serviceId = springCloudSelectorHandle.getServiceId();
        if (StringUtils.isBlank(serviceId)) {
            Object error = ShenyuResultWrap.error(exchange, ShenyuResultEnum.CANNOT_CONFIG_SPRINGCLOUD_SERVICEID);
            return WebFluxResultUtils.result(exchange, error);
        }
        // 访问者的ip，用于负载均衡
        final String ip = Objects.requireNonNull(exchange.getRequest().getRemoteAddress()).getAddress().getHostAddress();
        // 根据选择器规则配置的负载均衡策略，选择合适的服务实例
        final Upstream upstream = serviceChooser.choose(serviceId, selector.getId(), ip, ruleHandle.getLoadBalance());
        if (Objects.isNull(upstream)) {
            Object error = ShenyuResultWrap.error(exchange, ShenyuResultEnum.SPRINGCLOUD_SERVICEID_IS_ERROR);
            return WebFluxResultUtils.result(exchange, error);
        }
        // 设置调用地址信息(oip+port)
        final String domain = upstream.buildDomain();
        setDomain(URI.create(domain + shenyuContext.getRealUrl()), exchange);
        //set time out. 设置超时时间，由选择器规则配置
        exchange.getAttributes().put(Constants.HTTP_TIME_OUT, ruleHandle.getTimeout());
        return chain.execute(exchange);
    }

    @Override
    public int getOrder() {
        return PluginEnum.SPRING_CLOUD.getCode();
    }

    @Override
    public String named() {
        return PluginEnum.SPRING_CLOUD.getName();
    }

    /**
     * plugin is execute.
     *
     * @param exchange the current server exchange
     * @return default false.
     */
    @Override
    public boolean skip(final ServerWebExchange exchange) {
        return skipExcept(exchange, RpcTypeEnum.SPRING_CLOUD);
    }

    @Override
    protected Mono<Void> handleSelectorIfNull(final String pluginName, final ServerWebExchange exchange, final ShenyuPluginChain chain) {
        return WebFluxResultUtils.noSelectorResult(pluginName, exchange);
    }

    @Override
    protected Mono<Void> handleRuleIfNull(final String pluginName, final ServerWebExchange exchange, final ShenyuPluginChain chain) {
        return WebFluxResultUtils.noRuleResult(pluginName, exchange);
    }
    
    private SpringCloudRuleHandle buildRuleHandle(final RuleData rule) {
        if (StringUtils.isNotEmpty(rule.getId())) {
            return SpringCloudPluginDataHandler.RULE_CACHED.get().obtainHandle(CacheKeyUtils.INST.getKey(rule));
        } else {
            return defaultRuleHandle;
        }
    }

    private void setDomain(final URI uri, final ServerWebExchange exchange) {
        String domain = uri.getScheme() + "://" + uri.getAuthority();
        exchange.getAttributes().put(Constants.HTTP_DOMAIN, domain);
    }
}
