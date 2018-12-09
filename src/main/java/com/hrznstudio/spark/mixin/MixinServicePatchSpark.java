/*
 * This file is part of Mixin, licensed under the MIT License (MIT).
 *
 * Copyright (c) SpongePowered <https://www.spongepowered.org>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.hrznstudio.spark.mixin;

import com.google.common.collect.ImmutableList;
import com.hrznstudio.spark.patch.IBytePatcher;
import com.hrznstudio.spark.patch.PatchBlackboard;
import com.hrznstudio.spark.patch.PatcherRoster;
import org.apache.commons.io.IOUtils;
import org.spongepowered.asm.lib.ClassReader;
import org.spongepowered.asm.lib.tree.ClassNode;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.MixinEnvironment.Phase;
import org.spongepowered.asm.service.IClassBytecodeProvider;
import org.spongepowered.asm.service.IClassProvider;
import org.spongepowered.asm.service.ILegacyClassTransformer;
import org.spongepowered.asm.service.IMixinService;
import org.spongepowered.asm.service.ITransformer;
import org.spongepowered.asm.util.ReEntranceLock;
import org.spongepowered.asm.util.perf.Profiler;
import org.spongepowered.asm.util.perf.Profiler.Section;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class MixinServicePatchSpark implements IMixinService, IClassProvider, IClassBytecodeProvider {
    private final ReEntranceLock lock = new ReEntranceLock(1);

    @Override
    public String getName() {
        return "JarPatch";
    }

    @Override
    public boolean isValid() {
        try {
            Class.forName("com.hrznstudio.spark.patch.IPatchPlugin");
        } catch (Throwable t) {
            return false;
        }
        return true;
    }

    @Override
    public void prepare() {
    }

    @Override
    public Phase getInitialPhase() {
        return Phase.DEFAULT;
    }

    @Override
    public void init() {
    }

    @Override
    public ReEntranceLock getReEntranceLock() {
        return this.lock;
    }

    @Override
    public Collection<String> getPlatformAgents() {
        return ImmutableList.of();
    }

    @Override
    public IClassProvider getClassProvider() {
        return this;
    }

    @Override
    public IClassBytecodeProvider getBytecodeProvider() {
        return this;
    }

    @Override
    public Class<?> findClass(String name) throws ClassNotFoundException {
        throw new ClassNotFoundException(name);
    }

    @Override
    public Class<?> findClass(String name, boolean initialize) throws ClassNotFoundException {
        throw new ClassNotFoundException(name);
    }

    @Override
    public Class<?> findAgentClass(String name, boolean initialize) throws ClassNotFoundException {
        return Class.forName(name, initialize, this.getClass().getClassLoader());
    }

    @Override
    public void beginPhase() {
    }

    @Override
    public void checkEnv(Object bootSource) {
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        return this.getClass().getClassLoader().getResourceAsStream(name);
    }

    @Override
    public void registerInvalidClass(String className) {
    }

    @Override
    public boolean isClassLoaded(String className) {
        return false;
    }

    @Override
    public String getClassRestrictions(String className) {
        return "";
    }

    @Override
    public URL[] getClassPath() {
        return new URL[0];
    }

    @Override
    public Collection<ITransformer> getTransformers() {
        Collection<IBytePatcher> patchers = PatcherRoster.INSTANCE.getPatchers();
        List<ITransformer> wrapped = new ArrayList<>(patchers.size());
        for (IBytePatcher patcher : patchers) {
            if (patcher instanceof ITransformer) {
                wrapped.add((ITransformer) patcher);
            } else {
                wrapped.add(new WrapperLegacyTransformer(patcher));
            }
        }
        return wrapped;
    }

    @Override
    public byte[] getClassBytes(String name, String transformedName) throws IOException {
        byte[] bytes = PatchBlackboard.CONTEXT.get().readRawBytes(name);
        if (bytes != null) {
            return bytes;
        }

        byte[] classpathBytes = PatchBlackboard.CONTEXT.get().readClasspathBytes(name);
        if (classpathBytes != null) {
            return classpathBytes;
        }

        String path = name.replace('.', '/') + ".class";
        try (InputStream input = this.getClass().getClassLoader().getResourceAsStream(path)) {
            if (input == null) {
                return null;
            }
            return IOUtils.toByteArray(input);
        }
    }

    @Override
    public byte[] getClassBytes(String className, boolean runTransformers) throws ClassNotFoundException, IOException {
        String name = className.replace('/', '.');

        Profiler profiler = MixinEnvironment.getProfiler();
        Section loadTime = profiler.begin(Profiler.ROOT, "class.load");
        byte[] classBytes = this.getClassBytes(name, name);
        loadTime.end();

        if (runTransformers) {
            Section transformTime = profiler.begin(Profiler.ROOT, "class.transform");
            classBytes = this.applyTransformers(name, classBytes, profiler);
            transformTime.end();
        }

        if (classBytes == null) {
            throw new ClassNotFoundException(String.format("The specified class '%s' was not found", name));
        }

        return classBytes;
    }

    private byte[] applyTransformers(String name, byte[] basicClass, Profiler profiler) {
        MixinEnvironment environment = MixinEnvironment.getCurrentEnvironment();

        for (ILegacyClassTransformer transformer : environment.getTransformers()) {
            this.lock.clear();

            int pos = transformer.getName().lastIndexOf('.');
            String simpleName = transformer.getName().substring(pos + 1);
            Section transformTime = profiler.begin(Profiler.FINE, simpleName.toLowerCase());
            transformTime.setInfo(transformer.getName());
            basicClass = transformer.transformClassBytes(name, name, basicClass);
            transformTime.end();

            if (this.lock.isSet()) {
                environment.addTransformerExclusion(transformer.getName());
                this.lock.clear();
            }
        }

        return basicClass;
    }

    @Override
    public ClassNode getClassNode(String className) throws ClassNotFoundException, IOException {
        return this.parseClass(this.getClassBytes(className, true), 0);
    }

    private ClassNode parseClass(byte[] classBytes, int flags) {
        ClassNode classNode = new ClassNode();
        ClassReader classReader = new ClassReader(classBytes);
        classReader.accept(classNode, flags);
        return classNode;
    }

    @Override
    public final String getSideName() {
        return "CLIENT";
    }
}
