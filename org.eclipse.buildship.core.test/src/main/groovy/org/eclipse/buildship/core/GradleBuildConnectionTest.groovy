package org.eclipse.buildship.core

import java.util.function.Function

import org.gradle.tooling.BuildAction
import org.gradle.tooling.IntermediateResultHandler
import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.ResultHandler
import org.gradle.tooling.UnknownModelException
import org.gradle.tooling.model.GradleProject

import org.eclipse.core.runtime.FileLocator
import org.eclipse.core.runtime.NullProgressMonitor
import org.eclipse.core.runtime.preferences.IEclipsePreferences

import org.eclipse.buildship.core.internal.CorePlugin
import org.eclipse.buildship.core.internal.console.ProcessStreamsProvider
import org.eclipse.buildship.core.internal.test.fixtures.ProjectSynchronizationSpecification
import org.eclipse.buildship.core.internal.test.fixtures.TestProcessStreamProvider
import org.eclipse.buildship.core.internal.workspace.CompositeModelQuery

class GradleBuildConnectionTest extends ProjectSynchronizationSpecification {

    def setup() {
        registerService(ProcessStreamsProvider, new TestProcessStreamProvider(){})
    }

    def "Cannot run null action"() {
        when:
        GradleBuild gradleBuild = gradleBuildFor(dir('GradleBuildConnectionTest'))
        gradleBuild.withConnection(null, new NullProgressMonitor())

        then:
        thrown NullPointerException
    }

   def "Can query a model"() {
       setup:
       ResultHandler resultHandler = Mock(ResultHandler)

       when:
       File location = dir('GradleBuildConnectionTest_1')
       GradleBuild gradleBuild = gradleBuildFor(location)
       Function query = { ProjectConnection c -> c.model(GradleProject).get() }
       GradleProject model = gradleBuild.withConnection(query, new NullProgressMonitor())

       then:
       model.projectDirectory == location.canonicalFile

       when:
       location = dir('GradleBuildConnectionTest_2')
       gradleBuild = gradleBuildFor(location)
       query = { ProjectConnection c -> c.getModel(GradleProject) }
       model = gradleBuild.withConnection(query, new NullProgressMonitor())

       then:
       model.projectDirectory == location.canonicalFile

       when:
       location = dir('GradleBuildConnectionTest_3')
       gradleBuild = gradleBuildFor(location)
       query = { ProjectConnection c -> c.getModel(GradleProject, resultHandler) }
       gradleBuild.withConnection(query, new NullProgressMonitor())

       then:
       1 * resultHandler.onComplete({ GradleProject p -> p.projectDirectory == location.canonicalFile })
   }

   def "Can run a build"() {
       setup:
       File location = dir('GradleBuildConnectionTest') {
           file 'build.gradle', '''
               task myTask {
                   doLast {
                       logger.quiet 'Hello myTask'
                   }
               }
           '''
       }

       when:
       GradleBuild gradleBuild = gradleBuildFor(location)
       Function query = { ProjectConnection c -> c.newBuild().forTasks("myTask").run() }
       GradleProject model = gradleBuild.withConnection(query, new NullProgressMonitor())

       then:
       CorePlugin.processStreamsProvider().backgroundJobProcessStreams.out.contains 'Hello myTask'
   }

   def "Can run a test"() {
       setup:
       File location = dir('GradleBuildConnectionTest') {
           file 'build.gradle', """
               plugins {
                   id 'java'
               }

               ${jcenterRepositoryBlock}

               dependencies {
                   testCompile 'junit:junit:4.12'
               }

               test.testLogging {
                   events "passed"
               }
           """

           dir ('src/test/java') {
               file "SimpleTest.java", '''
                   public class SimpleTest {

                       @org.junit.Test
                       public void test() {
                           System.out.println("foobar");
                       }
                   }
               '''
           }
       }

       when:
       importAndWait(location)
       GradleBuild gradleBuild = gradleBuildFor(location)
       Function query = { ProjectConnection c -> c.newTestLauncher().withJvmTestClasses("SimpleTest").run() }
       GradleProject model = gradleBuild.withConnection(query, new NullProgressMonitor())

       then:
       CorePlugin.processStreamsProvider().backgroundJobProcessStreams.out.contains('SimpleTest > test PASSED')
   }

   def "Can execute an action"() {
       setup:
       IntermediateResultHandler resultHandler = Mock(IntermediateResultHandler)

       when:
       File location = dir('GradleBuildConnectionTest_1')
       GradleBuild gradleBuild = gradleBuildFor(location)
       Function query = { ProjectConnection c -> c.action(ideFriendlyCompositeModelQuery(GradleProject)).run() }
       Collection<GradleProject> result= gradleBuild.withConnection(query, new NullProgressMonitor())

       then:
       result[0].projectDirectory == location.canonicalFile

       when:
       location = dir('GradleBuildConnectionTest_2')
       gradleBuild = gradleBuildFor(location)
       query = { ProjectConnection c -> c.action().projectsLoaded(ideFriendlyCompositeModelQuery(GradleProject), resultHandler).build().run() }
       result = gradleBuild.withConnection(query, new NullProgressMonitor())

       then:
       1 * resultHandler.onComplete({ GradleProject p -> p.projectDirectory == location.canonicalFile })

       when:
       location = dir('GradleBuildConnectionTest_3')
       gradleBuild = gradleBuildFor(location)
       query = { ProjectConnection c -> c.action().buildFinished(ideFriendlyCompositeModelQuery(GradleProject), resultHandler).build().run() }
       result = gradleBuild.withConnection(query, new NullProgressMonitor())

       then:
       1 * resultHandler.onComplete({ GradleProject p -> p.projectDirectory == location.canonicalFile })
   }

   def "Exceptions are re-thrown to the client"() {
       setup:
       File location = dir('GradleBuildConnectionTest')
       GradleBuild gradleBuild = gradleBuildFor(location)

       when:
       Function action = { ProjectConnection c -> c.model(IEclipsePreferences).get() }
       gradleBuild.withConnection(action, new NullProgressMonitor())

       then:
       thrown UnknownModelException
   }

   private BuildAction ideFriendlyCompositeModelQuery(Class model) {
       // Gradle doesn't know about the Eclipse-specific class-loader so we need to load the class with a separate class loader
       ClassLoader coreClassloader = GradleBuildConnectionTest.class.getClassLoader()
       ClassLoader tapiClassloader = ProjectConnection.class.getClassLoader()
       URL actionRootUrl = FileLocator.resolve(coreClassloader.getResource(""))
       def ideFriendlyCustomActionClassLoader = new URLClassLoader([ actionRootUrl ] as URL[], tapiClassloader)
       Class<?> actionClass = ideFriendlyCustomActionClassLoader.loadClass(CompositeModelQuery.class.getName())
       actionClass.getConstructor(Class).newInstance(model);
   }
}