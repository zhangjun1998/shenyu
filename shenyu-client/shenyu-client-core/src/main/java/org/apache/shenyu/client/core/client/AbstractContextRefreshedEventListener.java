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

package org.apache.shenyu.client.core.client;

import com.google.common.collect.Lists;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.shenyu.client.apidocs.annotations.ApiDoc;
import org.apache.shenyu.client.apidocs.annotations.ApiModule;
import org.apache.shenyu.client.core.constant.ShenyuClientConstants;
import org.apache.shenyu.client.core.disruptor.ShenyuClientRegisterEventPublisher;
import org.apache.shenyu.client.core.exception.ShenyuClientIllegalArgumentException;
import org.apache.shenyu.common.enums.ApiHttpMethodEnum;
import org.apache.shenyu.common.enums.ApiSourceEnum;
import org.apache.shenyu.common.enums.ApiStateEnum;
import org.apache.shenyu.common.enums.RpcTypeEnum;
import org.apache.shenyu.common.utils.UriUtils;
import org.apache.shenyu.register.client.api.ShenyuClientRegisterRepository;
import org.apache.shenyu.register.common.config.PropertiesConfig;
import org.apache.shenyu.register.common.dto.ApiDocRegisterDTO;
import org.apache.shenyu.register.common.dto.MetaDataRegisterDTO;
import org.apache.shenyu.register.common.dto.URIRegisterDTO;
import org.apache.shenyu.register.common.enums.EventType;
import org.javatuples.Sextet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.util.ReflectionUtils;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * The type abstract context refreshed event listener.
 *
 * <p>See <a href="https://github.com/apache/shenyu/issues/415">upload dubbo metadata on ContextRefreshedEvent</a>
 */
public abstract class AbstractContextRefreshedEventListener<T, A extends Annotation> implements ApplicationListener<ContextRefreshedEvent> {
    
    protected static final Logger LOG = LoggerFactory.getLogger(AbstractContextRefreshedEventListener.class);
    
    /**
     * api path separator.
     */
    protected static final String PATH_SEPARATOR = "/";
    
    private final ShenyuClientRegisterEventPublisher publisher = ShenyuClientRegisterEventPublisher.getInstance();
    
    private final AtomicBoolean registered = new AtomicBoolean(false);
    
    private final String appName;
    
    private final String contextPath;
    
    private final String ipAndPort;
    
    private final String host;
    
    private final String port;
    
    /**
     * Instantiates a new context refreshed event listener.
     *
     * @param clientConfig                   the shenyu client config
     * @param shenyuClientRegisterRepository the shenyuClientRegisterRepository
     */
    public AbstractContextRefreshedEventListener(final PropertiesConfig clientConfig,
                                                 final ShenyuClientRegisterRepository shenyuClientRegisterRepository) {
        // 配置信息
        Properties props = clientConfig.getProps();
        this.appName = props.getProperty(ShenyuClientConstants.APP_NAME);
        this.contextPath = Optional.ofNullable(props.getProperty(ShenyuClientConstants.CONTEXT_PATH)).map(UriUtils::repairData).orElse("");
        if (StringUtils.isBlank(appName) && StringUtils.isBlank(contextPath)) {
            String errorMsg = "client register param must config the appName or contextPath";
            LOG.error(errorMsg);
            throw new ShenyuClientIllegalArgumentException(errorMsg);
        }
        this.ipAndPort = props.getProperty(ShenyuClientConstants.IP_PORT);
        this.host = props.getProperty(ShenyuClientConstants.HOST);
        this.port = props.getProperty(ShenyuClientConstants.PORT);
        // 启动事件发布器
        publisher.start(shenyuClientRegisterRepository);
    }

    /**
     * 监听上下文刷新事件
     */
    @Override
    public void onApplicationEvent(@NonNull final ContextRefreshedEvent event) {
        final ApplicationContext context = event.getApplicationContext();
        // 获取服务中需要网关进行RPC调用的类
        Map<String, T> beans = getBeans(context);
        if (MapUtils.isEmpty(beans)) {
            return;
        }
        // 保证该方法只执行一次
        if (!registered.compareAndSet(false, true)) {
            return;
        }
        // 构造URI数据并注册
        publisher.publishEvent(buildURIRegisterDTO(context, beans));
        // 构造元数据并注册
        beans.forEach(this::handle);
        Map<String, Object> apiModules = context.getBeansWithAnnotation(ApiModule.class);
        apiModules.forEach((k, v) -> {
            handleApiDoc(v, beans);
        });
    }

    private void handleApiDoc(final Object bean, final Map<String, T> beans) {
        Class<?> apiModuleClass = AopUtils.isAopProxy(bean) ? AopUtils.getTargetClass(bean) : bean.getClass();
        final Method[] methods = ReflectionUtils.getUniqueDeclaredMethods(apiModuleClass);
        for (Method method : methods) {
            if (method.isAnnotationPresent(ApiDoc.class)) {
                List<ApiDocRegisterDTO> apis = buildApiDocDTO(bean, method, beans);
                for (ApiDocRegisterDTO apiDocRegisterDTO : apis) {
                    publisher.publishEvent(apiDocRegisterDTO);
                }
            }
        }
    }

    private List<ApiDocRegisterDTO> buildApiDocDTO(final Object bean, final Method method, final Map<String, T> beans) {
        String apiDesc = Stream.of(method.getDeclaredAnnotations()).filter(item -> item instanceof ApiDoc).findAny().map(item -> {
            ApiDoc apiDoc = (ApiDoc) item;
            return apiDoc.desc();
        }).orElse("");
        Class<?> clazz = AopUtils.isAopProxy(bean) ? AopUtils.getTargetClass(bean) : bean.getClass();
        String superPath = buildApiSuperPath(clazz, AnnotatedElementUtils.findMergedAnnotation(clazz, getAnnotationType()));
        if (superPath.indexOf("*") > 0) {
            superPath = superPath.substring(0, superPath.lastIndexOf("/"));
        }
        Annotation annotation = AnnotatedElementUtils.findMergedAnnotation(clazz, getAnnotationType());
        if (Objects.isNull(annotation)) {
            return Lists.newArrayList();
        }
        Sextet<String[], String, String, ApiHttpMethodEnum[], RpcTypeEnum, String> sextet = buildApiDocSextet(method, annotation, beans);
        if (Objects.isNull(sextet)) {
            return Lists.newArrayList();
        }
        String contextPath = getContextPath();
        String[] value0 = sextet.getValue0();
        List<ApiDocRegisterDTO> list = Lists.newArrayList();
        for (String value : value0) {
            String apiPath = contextPath + superPath + value;
            ApiHttpMethodEnum[] value3 = sextet.getValue3();
            for (ApiHttpMethodEnum apiHttpMethodEnum : value3) {
                ApiDocRegisterDTO build = ApiDocRegisterDTO.builder()
                        .consume(sextet.getValue1())
                        .produce(sextet.getValue2())
                        .httpMethod(apiHttpMethodEnum.getValue())
                        .contextPath(contextPath)
                        .ext("{}")
                        .document("{}")
                        .rpcType(sextet.getValue4().getName())
                        .version(sextet.getValue5())
                        .apiDesc(apiDesc)
                        .apiPath(apiPath)
                        .apiSource(ApiSourceEnum.ANNOTATION_GENERATION.getValue())
                        .state(ApiStateEnum.PUBLISHED.getState())
                        .apiOwner("admin")
                        .eventType(EventType.REGISTER)
                        .build();
                list.add(build);
            }
        }
        return list;
    }

    protected abstract Sextet<String[], String, String, ApiHttpMethodEnum[], RpcTypeEnum, String> buildApiDocSextet(Method method, Annotation annotation, Map<String, T> beans);
    
    protected abstract Map<String, T> getBeans(ApplicationContext context);
    
    @SuppressWarnings("all")
    protected abstract URIRegisterDTO buildURIRegisterDTO(ApplicationContext context,
                                                          Map<String, T> beans);
    
    protected void handle(final String beanName, final T bean) {
        Class<?> clazz = getCorrectedClass(bean);
        // 获取对应的客户端注解
        final A beanShenyuClient = AnnotatedElementUtils.findMergedAnnotation(clazz, getAnnotationType());
        // 根据bean获取path
        final String superPath = buildApiSuperPath(clazz, beanShenyuClient);
        // Compatible with previous versions
        if (Objects.nonNull(beanShenyuClient) && superPath.contains("*")) {
            handleClass(clazz, bean, beanShenyuClient, superPath);
            return;
        }
        //获取当前bean所有方法并注册符合条件的方法
        final Method[] methods = ReflectionUtils.getUniqueDeclaredMethods(clazz);
        for (Method method : methods) {
            handleMethod(bean, clazz, beanShenyuClient, method, superPath);
        }
    }
    
    protected Class<?> getCorrectedClass(final T bean) {
        Class<?> clazz = bean.getClass();
        if (AopUtils.isAopProxy(bean)) {
            clazz = AopUtils.getTargetClass(bean);
        }
        return clazz;
    }
    
    protected abstract String buildApiSuperPath(Class<?> clazz,
                                                @Nullable A beanShenyuClient);
    
    protected void handleClass(final Class<?> clazz,
                               final T bean,
                               @NonNull final A beanShenyuClient,
                               final String superPath) {
        publisher.publishEvent(buildMetaDataDTO(bean, beanShenyuClient, pathJoin(contextPath, superPath), clazz, null));
    }

    protected void handleMethod(final T bean,
                                final Class<?> clazz,
                                @Nullable final A beanShenyuClient,
                                final Method method,
                                final String superPath) {
        A methodShenyuClient = AnnotatedElementUtils.findMergedAnnotation(method, getAnnotationType());
        if (Objects.nonNull(methodShenyuClient)) {
            // 构建元数据，发布事件
            publisher.publishEvent(buildMetaDataDTO(bean, methodShenyuClient, buildApiPath(method, superPath, methodShenyuClient), clazz, method));
        }
    }
    
    protected abstract Class<A> getAnnotationType();
    
    protected abstract String buildApiPath(Method method,
                                           String superPath,
                                           @NonNull A methodShenyuClient);
    
    protected String pathJoin(@NonNull final String... path) {
        StringBuilder result = new StringBuilder(PATH_SEPARATOR);
        for (String p : path) {
            if (!result.toString().endsWith(PATH_SEPARATOR)) {
                result.append(PATH_SEPARATOR);
            }
            result.append(p.startsWith(PATH_SEPARATOR) ? p.replaceFirst(PATH_SEPARATOR, "") : p);
        }
        return result.toString();
    }
    
    protected abstract MetaDataRegisterDTO buildMetaDataDTO(T bean,
                                                            @NonNull A shenyuClient,
                                                            String path,
                                                            Class<?> clazz,
                                                            Method method);
    
    /**
     * Get the event publisher.
     *
     * @return the event publisher
     */
    public ShenyuClientRegisterEventPublisher getPublisher() {
        return publisher;
    }
    
    /**
     * Get the app name.
     *
     * @return the app name
     */
    public String getAppName() {
        return appName;
    }
    
    /**
     * Get the context path.
     *
     * @return the context path
     */
    public String getContextPath() {
        return contextPath;
    }
    
    /**
     * Get the ip and port.
     *
     * @return the ip and port
     */
    public String getIpAndPort() {
        return ipAndPort;
    }
    
    /**
     * Get the host.
     *
     * @return the host
     */
    public String getHost() {
        return host;
    }
    
    /**
     * Get the port.
     *
     * @return the port
     */
    public String getPort() {
        return port;
    }
}
