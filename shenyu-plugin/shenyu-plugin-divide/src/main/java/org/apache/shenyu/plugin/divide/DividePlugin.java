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

package org.apache.shenyu.plugin.divide;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.shenyu.common.constant.Constants;
import org.apache.shenyu.common.dto.RuleData;
import org.apache.shenyu.common.dto.SelectorData;
import org.apache.shenyu.common.dto.convert.rule.impl.DivideRuleHandle;
import org.apache.shenyu.common.enums.LoadBalanceEnum;
import org.apache.shenyu.common.enums.PluginEnum;
import org.apache.shenyu.common.enums.RetryEnum;
import org.apache.shenyu.common.enums.RpcTypeEnum;
import org.apache.shenyu.loadbalancer.cache.UpstreamCacheManager;
import org.apache.shenyu.loadbalancer.entity.Upstream;
import org.apache.shenyu.loadbalancer.factory.LoadBalancerFactory;
import org.apache.shenyu.plugin.api.ShenyuPluginChain;
import org.apache.shenyu.plugin.api.context.ShenyuContext;
import org.apache.shenyu.plugin.api.result.ShenyuResultEnum;
import org.apache.shenyu.plugin.api.result.ShenyuResultWrap;
import org.apache.shenyu.plugin.api.utils.WebFluxResultUtils;
import org.apache.shenyu.plugin.base.AbstractShenyuPlugin;
import org.apache.shenyu.plugin.base.utils.CacheKeyUtils;
import org.apache.shenyu.plugin.divide.handler.DividePluginDataHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Divide Plugin.
 */
public class DividePlugin extends AbstractShenyuPlugin {

    private static final Logger LOG = LoggerFactory.getLogger(DividePlugin.class);
    
    private final DivideRuleHandle defaultRuleHandle = new DivideRuleHandle();

    @Override
    protected Mono<Void> doExecute(final ServerWebExchange exchange, final ShenyuPluginChain chain, final SelectorData selector, final RuleData rule) {
        ShenyuContext shenyuContext = exchange.getAttribute(Constants.CONTEXT);
        assert shenyuContext != null;
        // 从缓存中获取对应的规则处理器
        DivideRuleHandle ruleHandle = buildRuleHandle(rule);
        if (ruleHandle.getHeaderMaxSize() > 0) {
            long headerSize = exchange.getRequest().getHeaders().values()
                    .stream()
                    .flatMap(Collection::stream)
                    .mapToLong(header -> header.getBytes(StandardCharsets.UTF_8).length)
                    .sum();
            if (headerSize > ruleHandle.getHeaderMaxSize()) {
                LOG.error("request header is too large");
                Object error = ShenyuResultWrap.error(exchange, ShenyuResultEnum.REQUEST_HEADER_TOO_LARGE);
                return WebFluxResultUtils.result(exchange, error);
            }
        }
        if (ruleHandle.getRequestMaxSize() > 0) {
            if (exchange.getRequest().getHeaders().getContentLength() > ruleHandle.getRequestMaxSize()) {
                LOG.error("request entity is too large");
                Object error = ShenyuResultWrap.error(exchange, ShenyuResultEnum.REQUEST_ENTITY_TOO_LARGE);
                return WebFluxResultUtils.result(exchange, error);
            }
        }
        // 根据选择器id找到请求对应发往的上游服务(集群)
        List<Upstream> upstreamList = UpstreamCacheManager.getInstance().findUpstreamListBySelectorId(selector.getId());
        if (CollectionUtils.isEmpty(upstreamList)) {
            LOG.error("divide upstream configuration error： {}", selector);
            Object error = ShenyuResultWrap.error(exchange, ShenyuResultEnum.CANNOT_FIND_HEALTHY_UPSTREAM_URL);
            return WebFluxResultUtils.result(exchange, error);
        }
        String ip = Objects.requireNonNull(exchange.getRequest().getRemoteAddress()).getAddress().getHostAddress();
        // 根据选择器规则中配置的负载均衡策略定位应该请求的机器
        Upstream upstream = LoadBalancerFactory.selector(upstreamList, ruleHandle.getLoadBalance(), ip);
        if (Objects.isNull(upstream)) {
            LOG.error("divide has no upstream");
            Object error = ShenyuResultWrap.error(exchange, ShenyuResultEnum.CANNOT_FIND_HEALTHY_UPSTREAM_URL);
            return WebFluxResultUtils.result(exchange, error);
        }
        // set the http url
        // 请求头中指定了机器，使用请求头中指定的url
        if (CollectionUtils.isNotEmpty(exchange.getRequest().getHeaders().get(Constants.SPECIFY_DOMAIN))) {
            upstream.setUrl(exchange.getRequest().getHeaders().get(Constants.SPECIFY_DOMAIN).get(0));
        }
        // set domain 设置请求地址
        String domain = upstream.buildDomain();
        exchange.getAttributes().put(Constants.HTTP_DOMAIN, domain);
        // set the http timeout 设置超时时间和重试次数
        exchange.getAttributes().put(Constants.HTTP_TIME_OUT, ruleHandle.getTimeout());
        exchange.getAttributes().put(Constants.HTTP_RETRY, ruleHandle.getRetry());
        // set retry strategy stuff 设置重试策略、负载均衡策略、使用的选择器
        exchange.getAttributes().put(Constants.RETRY_STRATEGY, StringUtils.defaultString(ruleHandle.getRetryStrategy(), RetryEnum.CURRENT.getName()));
        exchange.getAttributes().put(Constants.LOAD_BALANCE, StringUtils.defaultString(ruleHandle.getLoadBalance(), LoadBalanceEnum.RANDOM.getName()));
        exchange.getAttributes().put(Constants.DIVIDE_SELECTOR_ID, selector.getId());
        return chain.execute(exchange);
    }

    @Override
    public String named() {
        return PluginEnum.DIVIDE.getName();
    }

    @Override
    public boolean skip(final ServerWebExchange exchange) {
        return skipExcept(exchange, RpcTypeEnum.HTTP);
    }

    @Override
    public int getOrder() {
        return PluginEnum.DIVIDE.getCode();
    }

    @Override
    protected Mono<Void> handleSelectorIfNull(final String pluginName, final ServerWebExchange exchange, final ShenyuPluginChain chain) {
        return WebFluxResultUtils.noSelectorResult(pluginName, exchange);
    }

    @Override
    protected Mono<Void> handleRuleIfNull(final String pluginName, final ServerWebExchange exchange, final ShenyuPluginChain chain) {
        return WebFluxResultUtils.noRuleResult(pluginName, exchange);
    }
    
    private DivideRuleHandle buildRuleHandle(final RuleData rule) {
        if (StringUtils.isNotEmpty(rule.getId())) {
            return DividePluginDataHandler.CACHED_HANDLE.get().obtainHandle(CacheKeyUtils.INST.getKey(rule));
        } else {
            return defaultRuleHandle;
        }
    }
}
