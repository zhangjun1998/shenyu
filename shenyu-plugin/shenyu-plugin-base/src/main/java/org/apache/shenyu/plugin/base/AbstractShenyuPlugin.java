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

package org.apache.shenyu.plugin.base;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.shenyu.common.config.ShenyuConfig;
import org.apache.shenyu.common.dto.ConditionData;
import org.apache.shenyu.common.dto.PluginData;
import org.apache.shenyu.common.dto.RuleData;
import org.apache.shenyu.common.dto.SelectorData;
import org.apache.shenyu.common.enums.MatchModeEnum;
import org.apache.shenyu.common.enums.SelectorTypeEnum;
import org.apache.shenyu.plugin.api.ShenyuPlugin;
import org.apache.shenyu.plugin.api.ShenyuPluginChain;
import org.apache.shenyu.plugin.api.utils.SpringBeanUtils;
import org.apache.shenyu.plugin.base.cache.BaseDataCache;
import org.apache.shenyu.plugin.base.cache.MatchDataCache;
import org.apache.shenyu.plugin.base.condition.strategy.MatchStrategyFactory;
import org.apache.shenyu.plugin.base.trie.ShenyuTrie;
import org.apache.shenyu.plugin.base.trie.ShenyuTrieNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 插件抽象类，需要进行流量匹配的插件继承此类
 * abstract shenyu plugin please extends.
 */
public abstract class AbstractShenyuPlugin implements ShenyuPlugin {

    private static final Logger LOG = LoggerFactory.getLogger(AbstractShenyuPlugin.class);

    private static final String URI_CONDITION_TYPE = "uri";

    private ShenyuConfig.MatchCache matchCacheConfig;

    /**
     * this is Template Method child has Implement your own logic.
     *
     * @param exchange exchange the current server exchange {@linkplain ServerWebExchange}
     * @param chain    chain the current chain  {@linkplain ServerWebExchange}
     * @param selector selector    {@linkplain SelectorData}
     * @param rule     rule    {@linkplain RuleData}
     * @return {@code Mono<Void>} to indicate when request handling is complete
     */
    protected abstract Mono<Void> doExecute(ServerWebExchange exchange, ShenyuPluginChain chain, SelectorData selector, RuleData rule);

    /**
     * 执行插件责任链抽象逻辑，最后会交由具体插件执行自定义逻辑
     *
     * Process the Web request and (optionally) delegate to the next
     * {@code ShenyuPlugin} through the given {@link ShenyuPluginChain}.
     *
     * @param exchange the current server exchange
     * @param chain    provides a way to delegate to the next plugin
     * @return {@code Mono<Void>} to indicate when request processing is complete
     */
    @Override
    public Mono<Void> execute(final ServerWebExchange exchange, final ShenyuPluginChain chain) {
        initMatchCacheConfig();
        // 根据plugin从缓存中找plugin，这里使用的是ConcurrentMap作为缓存
        final String pluginName = named();
        PluginData pluginData = BaseDataCache.getInstance().obtainPluginData(pluginName);
        // early exit plugin不存在或被禁用，结束该plugin继续向下执行责任链
        if (Objects.isNull(pluginData) || !pluginData.getEnabled()) {
            return chain.execute(exchange);
        }
        // 获取请求路径
        final String path = exchange.getRequest().getURI().getPath();
        // 从缓存中获取plugin对应的所有选择器
        List<SelectorData> selectors = BaseDataCache.getInstance().obtainSelectorData(pluginName);
        // 根据path获取对应选择器信息
        SelectorData selectorData = obtainSelectorDataCacheIfEnabled(path);
        // handle Selector，选择器配置异常，结束处理，继续向下执行责任链
        if (Objects.nonNull(selectorData) && StringUtils.isBlank(selectorData.getId())) {
            return handleSelectorIfNull(pluginName, exchange, chain);
        }
        // 缓存中没有获取到对应的selectorData，遍历所有选择器进行匹配
        if (Objects.isNull(selectorData)) {
            if (CollectionUtils.isEmpty(selectors)) {
                return handleSelectorIfNull(pluginName, exchange, chain);
            }
            Pair<Boolean, SelectorData> matchSelectorData = matchSelector(exchange, selectors);
            selectorData = matchSelectorData.getRight();
            if (Objects.isNull(selectorData)) {
                // 没有匹配到可用的选择器
                if (matchCacheConfig.getSelectorEnabled() && matchSelectorData.getLeft()) {
                    selectorData = new SelectorData();
                    selectorData.setPluginName(pluginName);
                    cacheSelectorData(path, selectorData);
                }
                // 结束处理，继续向下执行责任链
                return handleSelectorIfNull(pluginName, exchange, chain);
            } else {
                if (matchCacheConfig.getSelectorEnabled() && matchSelectorData.getLeft()) {
                    // 匹配成功，加入缓存
                    cacheSelectorData(path, selectorData);
                }
            }
        }
        printLog(selectorData, pluginName);
        // 是否需要继续后继规则处理，选择器为初筛粒度，规则为细粒度，不继续后继选择器则不进行规则处理直接执行
        if (Objects.nonNull(selectorData.getContinued()) && !selectorData.getContinued()) {
            // if continued， not match rules
            return doExecute(exchange, chain, selectorData, defaultRuleData(selectorData));
        }
        // 从缓存中获取选择器的所有规则配置
        List<RuleData> rules = BaseDataCache.getInstance().obtainRuleData(selectorData.getId());
        if (CollectionUtils.isEmpty(rules)) {
            return handleRuleIfNull(pluginName, exchange, chain);
        }
        RuleData ruleData;
        // 根据选择器适配的流量类型决定，自定义类型只有在满足选择器配置的条件时才会执行，全流量则必定执行
        if (selectorData.getType() == SelectorTypeEnum.FULL_FLOW.getCode()) {
            //get last 选取最后一条规则进行匹配
            RuleData rule = rules.get(rules.size() - 1);
            printLog(rule, pluginName);
            return doExecute(exchange, chain, selectorData, rule);
        } else {
            // match path with rule uri condition
            // 从缓存中根据uri匹配具体规则，这个缓存的结构是一个嵌套的Hash结构，key为url的每一层级
            // 很奇怪，这里的缓存使用的是Caffeine，而非是上面BaseDataCache中使用的ConcurrentMap
            ShenyuTrieNode matchTrieNode = SpringBeanUtils.getInstance().getBean(ShenyuTrie.class).match(path, selectorData.getId());
            if (Objects.nonNull(matchTrieNode)) {
                List<RuleData> ruleDataList = matchTrieNode.getPathRuleCache().getIfPresent(selectorData.getId());
                if (CollectionUtils.isNotEmpty(ruleDataList)) {
                    // 若存在多条匹配的规则，会任取一条
                    ruleData = genericMatchRule(exchange, ruleDataList);
                } else {
                    ruleData = genericMatchRule(exchange, rules);
                }
            } else {
                ruleData = genericMatchRule(exchange, rules);
            }
            if (Objects.isNull(ruleData)) {
                return handleRuleIfNull(pluginName, exchange, chain);
            }
        }
        printLog(ruleData, pluginName);
        // 执行选择器内部逻辑
        return doExecute(exchange, chain, selectorData, ruleData);
    }

    private void initMatchCacheConfig() {
        if (Objects.isNull(matchCacheConfig)) {
            matchCacheConfig = SpringBeanUtils.getInstance().getBean(ShenyuConfig.class).getMatchCache();
        }
    }

    private void cacheSelectorData(final String path, final SelectorData selectorData) {
        if (StringUtils.isBlank(selectorData.getId())) {
            MatchDataCache.getInstance().cacheSelectorData(path, selectorData, getSelectorMaxFreeMemory());
            return;
        }
        List<ConditionData> conditionList = selectorData.getConditionList();
        if (CollectionUtils.isNotEmpty(conditionList)) {
            boolean isUriCondition = conditionList.stream().allMatch(v -> URI_CONDITION_TYPE.equals(v.getParamType()));
            if (isUriCondition) {
                MatchDataCache.getInstance().cacheSelectorData(path, selectorData, getSelectorMaxFreeMemory());
            }
        }
    }

    private Integer getSelectorMaxFreeMemory() {
        return matchCacheConfig.getMaxSelectorFreeMemory() * 1024 * 1024;
    }

    private SelectorData obtainSelectorDataCacheIfEnabled(final String path) {
        return matchCacheConfig.getSelectorEnabled() ? MatchDataCache.getInstance().obtainSelectorData(named(), path) : null;
    }

    protected RuleData defaultRuleData(final SelectorData selectorData) {
        RuleData ruleData = new RuleData();
        ruleData.setSelectorId(selectorData.getId());
        ruleData.setPluginName(selectorData.getPluginName());
        return ruleData;
    }

    protected Mono<Void> handleSelectorIfNull(final String pluginName, final ServerWebExchange exchange, final ShenyuPluginChain chain) {
        return chain.execute(exchange);
    }

    protected Mono<Void> handleRuleIfNull(final String pluginName, final ServerWebExchange exchange, final ShenyuPluginChain chain) {
        return chain.execute(exchange);
    }

    private Pair<Boolean, SelectorData> matchSelector(final ServerWebExchange exchange, final Collection<SelectorData> selectors) {
        List<SelectorData> filterCollectors = selectors.stream()
                .filter(selector -> selector.getEnabled() && filterSelector(selector, exchange))
                .distinct()
                .collect(Collectors.toList());
        if (filterCollectors.size() > 1) {
            return Pair.of(Boolean.FALSE, manyMatchSelector(filterCollectors));
        } else {
            return Pair.of(Boolean.TRUE, filterCollectors.stream().findFirst().orElse(null));
        }
    }

    private SelectorData manyMatchSelector(final List<SelectorData> filterCollectors) {
        //What needs to be dealt with here is the and condition. If the number of and conditions is the same and is matched at the same time,
        // it will be sorted by the sort field.
        Map<Integer, List<Pair<Integer, SelectorData>>> collect =
                filterCollectors.stream().map(selector -> {
                    boolean match = MatchModeEnum.match(selector.getMatchMode(), MatchModeEnum.AND);
                    int sort = 0;
                    if (match) {
                        sort = selector.getConditionList().size();
                    }
                    return Pair.of(sort, selector);
                }).collect(Collectors.groupingBy(Pair::getLeft));
        Integer max = Collections.max(collect.keySet());
        List<Pair<Integer, SelectorData>> pairs = collect.get(max);
        return pairs.stream().map(Pair::getRight).min(Comparator.comparing(SelectorData::getSort)).orElse(null);
    }

    private Boolean filterSelector(final SelectorData selector, final ServerWebExchange exchange) {
        if (selector.getType() == SelectorTypeEnum.CUSTOM_FLOW.getCode()) {
            if (CollectionUtils.isEmpty(selector.getConditionList())) {
                return false;
            }
            return MatchStrategyFactory.match(selector.getMatchMode(), selector.getConditionList(), exchange);
        }
        return true;
    }

    private RuleData genericMatchRule(final ServerWebExchange exchange, final Collection<RuleData> rules) {
        Pair<Boolean, RuleData> genericMatchRule = this.matchRule(exchange, rules);
        if (genericMatchRule.getLeft()) {
            return genericMatchRule.getRight();
        } else {
            return null;
        }
    }

    private Pair<Boolean, RuleData> matchRule(final ServerWebExchange exchange, final Collection<RuleData> rules) {
        List<RuleData> filterRuleData = rules.stream()
                .filter(rule -> filterRule(rule, exchange))
                .distinct()
                .collect(Collectors.toList());
        if (filterRuleData.size() > 1) {
            return Pair.of(Boolean.TRUE, manyMatchRule(filterRuleData));
        } else {
            return Pair.of(Boolean.TRUE, filterRuleData.stream().findFirst().orElse(null));
        }
    }

    private RuleData manyMatchRule(final List<RuleData> filterRuleData) {
        Map<Integer, List<Pair<Integer, RuleData>>> collect =
                filterRuleData.stream().map(rule -> {
                    boolean match = MatchModeEnum.match(rule.getMatchMode(), MatchModeEnum.AND);
                    int sort = 0;
                    if (match) {
                        sort = rule.getConditionDataList().size();
                    }
                    return Pair.of(sort, rule);
                }).collect(Collectors.groupingBy(Pair::getLeft));
        Integer max = Collections.max(collect.keySet());
        List<Pair<Integer, RuleData>> pairs = collect.get(max);
        return pairs.stream().map(Pair::getRight).min(Comparator.comparing(RuleData::getSort)).orElse(null);
    }

    private Boolean filterRule(final RuleData ruleData, final ServerWebExchange exchange) {
        return ruleData.getEnabled() && MatchStrategyFactory.match(ruleData.getMatchMode(), ruleData.getConditionDataList(), exchange);
    }

    private void printLog(final Object data, final String pluginName) {
        if (data instanceof SelectorData) {
            SelectorData selector = (SelectorData) data;
            if (selector.getLogged()) {
                LOG.info("{} selector success match , selector name :{}", pluginName, selector.getName());
            }
        }
        if (data instanceof RuleData) {
            RuleData rule = (RuleData) data;
            if (rule.getLoged()) {
                LOG.info("{} rule success match , rule name :{}", pluginName, rule.getName());
            }
        }
    }
}
