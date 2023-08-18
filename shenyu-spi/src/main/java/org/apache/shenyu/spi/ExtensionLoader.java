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

package org.apache.shenyu.spi;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * SPI扩展加载器
 * 示例如下：
 * SPI接口：{@link org.apache.shenyu.register.client.server.api.ShenyuClientServerRegisterRepository}
 * 配置文件：{@see shenyu-register-center/shenyu-register-client-server/shenyu-register-client-server-zookeeper/src/main/resources/META-INF/shenyu/org.apache.shenyu.register.client.server.api.ShenyuClientServerRegisterRepository}
 *
 * The type Extension loader.
 * This is done by loading the properties file.
 *
 * @param <T> the type parameter
 * @see <a href="https://github.com/apache/dubbo/blob/master/dubbo-common/src/main/java/org/apache/dubbo/common/extension/ExtensionLoader.java">ExtensionLoader</a>
 */
@SuppressWarnings("all")
public final class ExtensionLoader<T> {
    
    private static final Logger LOG = LoggerFactory.getLogger(ExtensionLoader.class);

    /**
     * SPI配置文件位置
     */
    private static final String SHENYU_DIRECTORY = "META-INF/shenyu/";

    /**
     * SPI扩展加载器实例缓存，静态变量所有实例全局共享，实现缓存，线程安全
     */
    private static final Map<Class<?>, ExtensionLoader<?>> LOADERS = new ConcurrentHashMap<>();

    /**
     * SPI注解标记的接口类型
     */
    private final Class<T> clazz;

    /**
     * 类加载器实例
     */
    private final ClassLoader classLoader;

    /**
     * 已加载的实现类信息，key=SPI配置文件下的类别名，value=别名对应的类信息
     */
    private final Holder<Map<String, ClassEntity>> cachedClasses = new Holder<>();

    /**
     * 已加载的类实例信息，key=SPI配置文件下的类别名，value=类实例
     */
    private final Map<String, Holder<Object>> cachedInstances = new ConcurrentHashMap<>();

    /**
     * 类实例缓存，key=Clazz，value=Instance
     */
    private final Map<Class<?>, Object> joinInstances = new ConcurrentHashMap<>();
    
    private String cachedDefaultName;
    
    private final Comparator<Holder<Object>> holderComparator = Comparator.comparing(Holder::getOrder);
    
    private final Comparator<ClassEntity> classEntityComparator = Comparator.comparing(ClassEntity::getOrder);
    
    /**
     * 私有构造器，不允许直接调用构造方法创建，只能通过静态方法获取实例
     * Instantiates a new Extension loader.
     *
     * @param clazz the clazz.
     */
    private ExtensionLoader(final Class<T> clazz, final ClassLoader cl) {
        this.clazz = clazz;
        this.classLoader = cl;
        if (!Objects.equals(clazz, ExtensionFactory.class)) {
            ExtensionLoader.getExtensionLoader(ExtensionFactory.class).getExtensionClassesEntity();
        }
    }
    
    /**
     * 对外暴露静态方法，获取SPI扩展加载器的实例对象
     * Gets extension loader.
     *
     * @param <T>   the type parameter
     * @param clazz the clazz
     * @param cl    the cl
     * @return the extension loader.
     */
    public static <T> ExtensionLoader<T> getExtensionLoader(final Class<T> clazz, final ClassLoader cl) {
        // 前置校验，判断需要加载的clazz是否为SPI注解标识的接口
        Objects.requireNonNull(clazz, "extension clazz is null");
        if (!clazz.isInterface()) {
            throw new IllegalArgumentException("extension clazz (" + clazz + ") is not interface!");
        }
        if (!clazz.isAnnotationPresent(SPI.class)) {
            throw new IllegalArgumentException("extension clazz (" + clazz + ") without @" + SPI.class + " Annotation");
        }
        // 从缓存中获取SPI扩展类加载器
        ExtensionLoader<T> extensionLoader = (ExtensionLoader<T>) LOADERS.get(clazz);
        if (Objects.nonNull(extensionLoader)) {
            return extensionLoader;
        }
        // 缓存中不存在，创建并放入缓存
        LOADERS.putIfAbsent(clazz, new ExtensionLoader<>(clazz, cl));
        return (ExtensionLoader<T>) LOADERS.get(clazz);
    }
    
    /**
     * 对外暴露静态方法，获取SPI扩展加载器的实例对象，使用 ExtensionLoader 的类加载器加载SPI实现类
     * Gets extension loader.
     *
     * @param <T>   the type parameter
     * @param clazz the clazz
     * @return the extension loader
     */
    public static <T> ExtensionLoader<T> getExtensionLoader(final Class<T> clazz) {
        return getExtensionLoader(clazz, ExtensionLoader.class.getClassLoader());
    }
    
    /**
     * 获取SPI的默认实例对象
     * Gets default join.
     *
     * @return the default join.
     */
    public T getDefaultJoin() {
        // 先走一遍扩展类加载流程，为了初始化 cachedDefaultName，以及 cachedClasses 缓存
        getExtensionClassesEntity();
        if (StringUtils.isBlank(cachedDefaultName)) {
            return null;
        }
        // 根据SPI注解中指定的默认实现类别名获取实例
        return getJoin(cachedDefaultName);
    }
    
    /**
     * 根据实现类别名获取对应类实例
     * Gets join.
     *
     * @param name the name
     * @return the join.
     */
    public T getJoin(final String name) {
        if (StringUtils.isBlank(name)) {
            throw new NullPointerException("get join name is null");
        }
        // 根据别名从缓存中获取实例持有者
        Holder<Object> objectHolder = cachedInstances.get(name);
        if (Objects.isNull(objectHolder)) {
            cachedInstances.putIfAbsent(name, new Holder<>());
            objectHolder = cachedInstances.get(name);
        }
        Object value = objectHolder.getValue();
        // 缓存中不存在，创建
        if (Objects.isNull(value)) {
            synchronized (cachedInstances) {
                value = objectHolder.getValue();
                if (Objects.isNull(value)) {
                    // 创建实例
                    createExtension(name, objectHolder);
                    value = objectHolder.getValue();
                    // 非单例模式，移除缓存
                    if (!objectHolder.isSingleton()) {
                        Holder<Object> removeObj = cachedInstances.remove(name);
                        removeObj = null;
                    }
                }
            }
        }
        return (T) value;
    }
    
    /**
     * 获取SPI接口所有实现类的实例集合
     * get all join spi.
     *
     * @return list. joins
     */
    public List<T> getJoins() {
        Map<String, ClassEntity> extensionClassesEntity = this.getExtensionClassesEntity();
        if (extensionClassesEntity.isEmpty()) {
            return Collections.emptyList();
        }
        // 所有实现类实例在缓存中都可以找到，直接走缓存
        if (Objects.equals(extensionClassesEntity.size(), cachedInstances.size())) {
            return (List<T>) this.cachedInstances.values().stream()
                    .sorted(holderComparator)
                    .map(e -> {
                        return e.getValue();
                    }).collect(Collectors.toList());
        }
        // 缓存中缺失，部分走缓存，部分即时创建
        List<T> joins = new ArrayList<>();
        List<ClassEntity> classEntities = extensionClassesEntity.values().stream()
                .sorted(classEntityComparator).collect(Collectors.toList());
        classEntities.forEach(v -> {
            T join = this.getJoin(v.getName());
            joins.add(join);
        });
        return joins;
    }

    /**
     * 创建实例
     */
    @SuppressWarnings("unchecked")
    private void createExtension(final String name, final Holder<Object> holder) {
        // 类信息
        ClassEntity classEntity = getExtensionClassesEntity().get(name);
        if (Objects.isNull(classEntity)) {
            throw new IllegalArgumentException(name + "name is error");
        }
        // 根据 Class 对象创建实例，Class.newInstance() 调用的是无参构造器，因此SPI实现类必须有无参构造器
        // 无参构造器导致SPI实现类中有些属性难以注入，这里可以考虑加一个注解标记SPI实现类中的指定方法用于在创建实例后进行回调做一些自定义操作
        Class<?> aClass = classEntity.getClazz();
        Object o = joinInstances.get(aClass);
        if (Objects.isNull(o)) {
            try {
                // 单例模式，直接放入缓存留待下次直接取
                if (classEntity.isSingleton()) {
                    joinInstances.putIfAbsent(aClass, aClass.newInstance());
                    o = joinInstances.get(aClass);
                } else {
                    // 原型模式，每次重新创建
                    o = aClass.newInstance();
                }
            } catch (InstantiationException | IllegalAccessException e) {
                throw new IllegalStateException("Extension instance(name: " + name + ", class: "
                        + aClass + ")  could not be instantiated: " + e.getMessage(), e);
                
            }
        }
        holder.setOrder(classEntity.getOrder());
        holder.setValue(o);
        holder.setSingleton(classEntity.isSingleton());
    }
    
    /**
     * Gets extension classes.
     *
     * @return the extension classes
     */
    public Map<String, Class<?>> getExtensionClasses1() {
        Map<String, ClassEntity> classes = this.getExtensionClassesEntity();
        return classes.values().stream().collect(Collectors.toMap(e -> e.getName(), x -> x.getClazz(), (a, b) -> a));
    }
    
    private Map<String, ClassEntity> getExtensionClassesEntity() {
        Map<String, ClassEntity> classes = cachedClasses.getValue();
        if (Objects.isNull(classes)) {
            synchronized (cachedClasses) {
                classes = cachedClasses.getValue();
                if (Objects.isNull(classes)) {
                    classes = loadExtensionClass();
                    cachedClasses.setValue(classes);
                    cachedClasses.setOrder(0);
                }
            }
        }
        return classes;
    }

    /**
     * 加载扩展类
     */
    private Map<String, ClassEntity> loadExtensionClass() {
        SPI annotation = clazz.getAnnotation(SPI.class);
        if (Objects.nonNull(annotation)) {
            String value = annotation.value();
            if (StringUtils.isNotBlank(value)) {
                cachedDefaultName = value;
            }
        }
        Map<String, ClassEntity> classes = new HashMap<>(16);
        loadDirectory(classes);
        return classes;
    }
    
    /**
     * 根据类全限定名称加载 SHENYU_DIRECTORY 下的SPI配置
     * Load files under SHENYU_DIRECTORY.
     */
    private void loadDirectory(final Map<String, ClassEntity> classes) {
        String fileName = SHENYU_DIRECTORY + clazz.getName();
        try {
            Enumeration<URL> urls = Objects.nonNull(this.classLoader) ? classLoader.getResources(fileName)
                    : ClassLoader.getSystemResources(fileName);
            if (Objects.nonNull(urls)) {
                while (urls.hasMoreElements()) {
                    URL url = urls.nextElement();
                    loadResources(classes, url);
                }
            }
        } catch (IOException t) {
            LOG.error("load extension class error {}", fileName, t);
        }
    }

    /**
     * 加载SPI配置下的资源文件
     */
    private void loadResources(final Map<String, ClassEntity> classes, final URL url) throws IOException {
        try (InputStream inputStream = url.openStream()) {
            Properties properties = new Properties();
            properties.load(inputStream);
            properties.forEach((k, v) -> {
                String name = (String) k;
                String classPath = (String) v;
                if (StringUtils.isNotBlank(name) && StringUtils.isNotBlank(classPath)) {
                    try {
                        loadClass(classes, name, classPath);
                    } catch (ClassNotFoundException e) {
                        throw new IllegalStateException("load extension resources error", e);
                    }
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("load extension resources error", e);
        }
    }

    /**
     * 加载SPI实现类
     */
    private void loadClass(final Map<String, ClassEntity> classes,
                           final String name, final String classPath) throws ClassNotFoundException {
        Class<?> subClass = Objects.nonNull(this.classLoader) ? Class.forName(classPath, true, this.classLoader) : Class.forName(classPath);
        if (!clazz.isAssignableFrom(subClass)) {
            throw new IllegalStateException("load extension resources error," + subClass + " subtype is not of " + clazz);
        }
        if (!subClass.isAnnotationPresent(Join.class)) {
            throw new IllegalStateException("load extension resources error," + subClass + " without @" + Join.class + " annotation");
        }
        ClassEntity oldClassEntity = classes.get(name);
        if (Objects.isNull(oldClassEntity)) {
            Join joinAnnotation = subClass.getAnnotation(Join.class);
            ClassEntity classEntity = new ClassEntity(name, joinAnnotation.order(), subClass, joinAnnotation.isSingleton());
            classes.put(name, classEntity);
        } else if (!Objects.equals(oldClassEntity.getClazz(), subClass)) {
            throw new IllegalStateException("load extension resources error,Duplicate class " + clazz.getName() + " name "
                    + name + " on " + oldClassEntity.getClazz().getName() + " or " + subClass.getName());
        }
    }
    
    /**
     * The type Holder.
     *
     * @param <T> the type parameter.
     */
    private static final class Holder<T> {
        
        private volatile T value;
        
        private Integer order;
        
        private boolean isSingleton;
        
        /**
         * Gets value.
         *
         * @return the value
         */
        public T getValue() {
            return value;
        }
        
        /**
         * Sets value.
         *
         * @param value the value
         */
        public void setValue(final T value) {
            this.value = value;
        }
        
        /**
         * set order.
         *
         * @param order order.
         */
        public void setOrder(final Integer order) {
            this.order = order;
        }
        
        /**
         * get order.
         *
         * @return order.
         */
        public Integer getOrder() {
            return order;
        }
        
        /**
         * Is it a singleton object.
         *
         * @return true or false.
         */
        public boolean isSingleton() {
            return isSingleton;
        }
        
        /**
         * Is it a singleton object.
         *
         * @param singleton true or false.
         */
        public void setSingleton(final boolean singleton) {
            isSingleton = singleton;
        }
    }
    
    private static final class ClassEntity {
        
        /**
         * name.
         */
        private String name;
        
        /**
         * order.
         */
        private Integer order;
        
        private Boolean isSingleton;
        
        /**
         * class.
         */
        private Class<?> clazz;
        
        private ClassEntity(final String name, final Integer order, final Class<?> clazz, final boolean isSingleton) {
            this.name = name;
            this.order = order;
            this.clazz = clazz;
            this.isSingleton = isSingleton;
        }
        
        /**
         * get class.
         *
         * @return class.
         */
        public Class<?> getClazz() {
            return clazz;
        }
        
        /**
         * set class.
         *
         * @param clazz class.
         */
        public void setClazz(final Class<?> clazz) {
            this.clazz = clazz;
        }
        
        /**
         * get name.
         *
         * @return name.
         */
        public String getName() {
            return name;
        }
        
        /**
         * get order.
         *
         * @return order.
         * @see Join#order()
         */
        public Integer getOrder() {
            return order;
        }
        
        /**
         * Obtaining this class requires creating a singleton object, or multiple instances.
         *
         * @return true or false.
         * @see Join#isSingleton()
         */
        public Boolean isSingleton() {
            return isSingleton;
        }
    }
}
