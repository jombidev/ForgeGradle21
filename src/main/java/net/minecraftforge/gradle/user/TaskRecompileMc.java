/*
 * A Gradle plugin for the creation of Minecraft mods and MinecraftForge plugins.
 * Copyright (C) 2013 Minecraft Forge
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 * USA
 */
package net.minecraftforge.gradle.user;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;
import com.google.common.io.Files;
import net.minecraftforge.gradle.tasks.CreateStartTask;
import net.minecraftforge.gradle.util.caching.Cached;
import net.minecraftforge.gradle.util.caching.CachedTask;
import org.gradle.api.AntBuilder;
import org.gradle.api.file.FileVisitDetails;
import org.gradle.api.file.FileVisitor;
import org.gradle.api.tasks.*;

import java.io.File;
import java.io.IOException;
import java.util.Set;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class TaskRecompileMc extends CachedTask {
    @InputFile
    private Object inSources;

    @Optional
    @InputFile
    private Object inResources;

    @Input
    private String classpath;

    @Cached
    @OutputFile
    private Object outJar;

    private static String getExtPath() {
        String currentExtDirs = System.getProperty("java.ext.dirs");
        StringBuilder newExtDirs = new StringBuilder();
        String[] parts = currentExtDirs.split(File.pathSeparator);
        if (parts.length > 0) {
            String lastPart = parts[parts.length - 1];
            for (String part : parts) {
                if (!part.equals("/System/Library/Java/Extensions")) {
                    newExtDirs.append(part);
                    if (!part.equals(lastPart)) {
                        newExtDirs.append(File.pathSeparator);
                    }
                }
            }
        }
        System.setProperty("java.ext.dirs", newExtDirs.toString());
        return newExtDirs.toString();
    }

    private static void extractSources(File tempDir, File inJar) throws IOException {
        ZipInputStream zin = new ZipInputStream(java.nio.file.Files.newInputStream(inJar.toPath()));
        ZipEntry entry;

        while ((entry = zin.getNextEntry()) != null) {
            // we dont care about directories.. we can make em later when needed
            // we only want java files to compile too, can grab the other resources from the jar later
            if (entry.isDirectory() || !entry.getName().endsWith(".java"))
                continue;

            File out = new File(tempDir, entry.getName());
            out.getParentFile().mkdirs();
            Files.asByteSink(out).writeFrom(zin);
        }

        zin.close();
    }

    @TaskAction
    public void doStuff() throws IOException {
        File inJar = getInSources();
        File tempSrc = new File(getTemporaryDir(), "sources");
        File tempCls = new File(getTemporaryDir(), "compiled");
        File outJar = getOutJar();

        // delete and recreate dirs
        getProject().delete(tempSrc, tempCls);
        tempSrc.mkdirs();
        tempCls.mkdirs();

        // extract sources
        extractSources(tempSrc, inJar);

        AntBuilder ant = CreateStartTask.setupAnt(this);
        getExtPath();
        // recompile
        ant.invokeMethod("javac",
                ImmutableMap.builder()
                        .put("srcDir", tempSrc.getCanonicalPath())
                        .put("destDir", tempCls.getCanonicalPath())
                        .put("failonerror", true)
                        .put("includeantruntime", false)
                        .put("classpath", getProject().getConfigurations().getByName(classpath).getAsPath())
                        .put("encoding", "utf-8")
                        .put("source", "1.8")
                        .put("target", "1.8")
                        .put("debug", "true")
                        //.put("Djava.ext.dirs", )
                        .build()
        );

        outJar.getParentFile().mkdirs();
        createOutput(outJar, inJar, tempCls, getInResources());
    }

    private void createOutput(File outJar, File sourceJar, File classDir, File resourceJar) throws IOException {
        Set<String> elementsAdded = Sets.newHashSet();

        // make output
        JarOutputStream zout = new JarOutputStream(java.nio.file.Files.newOutputStream(outJar.toPath()));

        Visitor visitor = new Visitor(zout, elementsAdded);

        // custom resources should override existing ones, so resources first.
        if (resourceJar != null) {
            getProject().zipTree(resourceJar).visit(visitor);
        }

        getProject().zipTree(sourceJar).visit(visitor); // then the ones from the the original sources
        getProject().fileTree(classDir).visit(visitor); // then the classes

        zout.close();
    }

    public File getInSources() {
        return getProject().file(inSources);
    }

    public void setInSources(Object inSources) {
        this.inSources = inSources;
    }

    public File getInResources() {
        if (inResources == null)
            return null;
        else
            return getProject().file(inResources);
    }

    public void setInResources(Object inResources) {
        this.inResources = inResources;
    }

    public String getClasspath() {
        return classpath;
    }

    public void setClasspath(String classpath) {
        this.classpath = classpath;
    }

    public File getOutJar() {
        return getProject().file(outJar);
    }

    public void setOutJar(Object outJar) {
        this.outJar = outJar;
    }

    private static final class Visitor implements FileVisitor {
        private final ZipOutputStream zout;
        private final Set<String> entries;

        public Visitor(ZipOutputStream zout, Set<String> entries) {
            this.zout = zout;
            this.entries = entries;
        }

        @Override
        public void visitDir(FileVisitDetails dir) {
            try {
                String name = dir.getRelativePath().toString().replace('\\', '/');
                if (!name.endsWith("/"))
                    name += "/";

                if (entries.contains(name))
                    return;

                entries.add(name);
                ZipEntry entry = new ZipEntry(name);
                zout.putNextEntry(entry);
            } catch (IOException e) {
                Throwables.throwIfUnchecked(e);
            }
        }

        @Override
        public void visitFile(FileVisitDetails file) {
            try {
                String name = file.getRelativePath().toString().replace('\\', '/');

                if (entries.contains(name) || name.endsWith(".java"))
                    return;

                entries.add(name);
                zout.putNextEntry(new ZipEntry(name));

                file.copyTo(zout);
            } catch (IOException e) {
                Throwables.throwIfUnchecked(e);
            }
        }
    }
}
