/*******************************************************************************
 * Copyright (c) 2020 Microsoft Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Microsoft Corporation - initial API and implementation
 *******************************************************************************/

package org.eclipse.jdt.ls.core.internal.handlers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jdt.ls.core.internal.ActionableNotification;
import org.eclipse.jdt.ls.core.internal.JavaClientConnection;
import org.eclipse.jdt.ls.core.internal.ResourceUtils;
import org.eclipse.jdt.ls.core.internal.managers.AbstractInvisibleProjectBasedTest;
import org.eclipse.jdt.ls.core.internal.managers.InvisibleProjectBuildSupport;
import org.eclipse.jdt.ls.core.internal.managers.ProjectsManager.CHANGE_TYPE;
import org.eclipse.jdt.ls.core.internal.preferences.Preferences.FeatureStatus;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class ClasspathUpdateHandlerTest extends AbstractInvisibleProjectBasedTest {
	@Mock
	private JavaClientConnection connection;

	private ClasspathUpdateHandler handler;

	@Before
	public void setup() throws Exception {
		handler = new ClasspathUpdateHandler(connection);
		handler.addElementChangeListener();
		preferences.setUpdateBuildConfigurationStatus(FeatureStatus.automatic);
	}

	@After
	@Override
	public void cleanUp() throws Exception {
		super.cleanUp();
		handler.removeElementChangeListener();
	}

	@Test
	public void testClasspathUpdateForMaven() throws Exception {
		importProjects("maven/salut");
		IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject("salut");
		IFile pom = project.getFile("/pom.xml");
		assertTrue(pom.exists());
		ResourceUtils.setContent(pom, ResourceUtils.getContent(pom).replaceAll("<version>3.5</version>", "<version>3.6</version>"));

		ResourcesPlugin.getWorkspace().build(IncrementalProjectBuilder.FULL_BUILD, monitor);
		waitForBackgroundJobs();

		ArgumentCaptor<ActionableNotification> argument = ArgumentCaptor.forClass(ActionableNotification.class);
		verify(connection, times(1)).sendActionableNotification(argument.capture());
		assertEquals(ClasspathUpdateHandler.CLASSPATH_UPDATED_NOTIFICATION, argument.getValue().getMessage());
		// Use Paths.get() to normalize the URI: ignore the tailing slash, "/project/path" and "/project/path/" should be the same.
		assertEquals(Paths.get(project.getLocationURI().toString()), Paths.get((String) argument.getValue().getData()));
	}

	@Test
	public void testClasspathUpdateForGradle() throws Exception {
		importProjects("gradle/simple-gradle");
		IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject("simple-gradle");
		IFile buildGradle = project.getFile("/build.gradle");
		assertTrue(buildGradle.exists());
		ResourceUtils.setContent(buildGradle, ResourceUtils.getContent(buildGradle).replaceAll("org.slf4j:slf4j-api:1.7.21", "org.slf4j:slf4j-api:1.7.20"));

		projectsManager.fileChanged(buildGradle.getLocationURI().toString(), CHANGE_TYPE.CHANGED);
		waitForBackgroundJobs();

		ArgumentCaptor<ActionableNotification> argument = ArgumentCaptor.forClass(ActionableNotification.class);
		verify(connection, times(1)).sendActionableNotification(argument.capture());
		assertEquals(ClasspathUpdateHandler.CLASSPATH_UPDATED_NOTIFICATION, argument.getValue().getMessage());
		assertEquals(Paths.get(project.getLocationURI().toString()), Paths.get((String) argument.getValue().getData()));
	}

	@Test
	public void testClasspathUpdateForEclipse() throws Exception {
		importProjects("eclipse/updatejar");
		IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject("updatejar");
		IFile classpath = project.getFile("/.classpath");
		assertTrue(classpath.exists());
		ResourceUtils.setContent(classpath, ResourceUtils.getContent(classpath).replaceAll("<classpathentry kind=\"lib\" path=\"lib/foo.jar\"/>", ""));

		projectsManager.fileChanged(classpath.getLocationURI().toString(), CHANGE_TYPE.CHANGED);
		waitForBackgroundJobs();

		ArgumentCaptor<ActionableNotification> argument = ArgumentCaptor.forClass(ActionableNotification.class);
		verify(connection, times(1)).sendActionableNotification(argument.capture());
		assertEquals(ClasspathUpdateHandler.CLASSPATH_UPDATED_NOTIFICATION, argument.getValue().getMessage());
		assertEquals(Paths.get(project.getLocationURI().toString()), Paths.get((String) argument.getValue().getData()));
	}

	@Test
	public void testClasspathUpdateForInvisble() throws Exception {
		File projectFolder = createSourceFolderWithMissingLibs("dynamicLibDetection");
		importRootFolder(projectFolder, "Test.java");

		//Add jars to fix compilation errors
		addLibs(projectFolder.toPath());
		Path libPath = projectFolder.toPath().resolve(InvisibleProjectBuildSupport.LIB_FOLDER);

		Path jar = libPath.resolve("foo.jar");
		projectsManager.fileChanged(jar.toUri().toString(), CHANGE_TYPE.CREATED);
		waitForBackgroundJobs();

		ArgumentCaptor<ActionableNotification> argument = ArgumentCaptor.forClass(ActionableNotification.class);
		verify(connection, times(1)).sendActionableNotification(argument.capture());
		assertEquals(ClasspathUpdateHandler.CLASSPATH_UPDATED_NOTIFICATION, argument.getValue().getMessage());
		assertEquals(Paths.get(projectFolder.toURI().toString()), Paths.get((String) argument.getValue().getData()));
		
	}
}