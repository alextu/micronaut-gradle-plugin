package io.micronaut.gradle.docker;

import com.bmuschko.gradle.docker.DockerExtension;
import com.bmuschko.gradle.docker.tasks.container.DockerCopyFileFromContainer;
import com.bmuschko.gradle.docker.tasks.container.DockerCreateContainer;
import com.bmuschko.gradle.docker.tasks.container.DockerRemoveContainer;
import com.bmuschko.gradle.docker.tasks.image.DockerBuildImage;
import com.bmuschko.gradle.docker.tasks.image.DockerPushImage;
import com.bmuschko.gradle.docker.tasks.image.Dockerfile;
import io.micronaut.gradle.MicronautBasePlugin;
import io.micronaut.gradle.MicronautExtension;
import io.micronaut.gradle.MicronautRuntime;
import io.micronaut.gradle.PluginsHelper;
import io.micronaut.gradle.docker.model.DefaultMicronautDockerImage;
import io.micronaut.gradle.docker.model.LayerKind;
import io.micronaut.gradle.docker.model.MicronautDockerImage;
import io.micronaut.gradle.docker.model.RuntimeKind;
import io.micronaut.gradle.docker.tasks.BuildLayersTask;
import io.micronaut.gradle.docker.tasks.PrepareDockerContext;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.RegularFile;
import org.gradle.api.plugins.BasePlugin;
import org.gradle.api.plugins.ExtensionContainer;
import org.gradle.api.plugins.JavaApplication;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Jar;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.micronaut.gradle.Strings.capitalize;
import static org.gradle.api.plugins.JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME;

public class MicronautDockerPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(MicronautBasePlugin.class);
        TaskContainer tasks = project.getTasks();
        ExtensionContainer extensions = project.getExtensions();
        MicronautExtension micronautExtension = extensions.getByType(MicronautExtension.class);
        NamedDomainObjectContainer<MicronautDockerImage> dockerImages = project.getObjects().domainObjectContainer(MicronautDockerImage.class, s -> project.getObjects().newInstance(DefaultMicronautDockerImage.class, s));
        micronautExtension.getExtensions().add("dockerImages", dockerImages);
        extensions.create("docker", DockerExtension.class);
        dockerImages.all(image -> createDockerImage(project, image));
        TaskProvider<Jar> runnerJar = createMainRunnerJar(project, tasks);
        dockerImages.create("main", image -> {
            image.addLayer(layer -> {
                layer.getLayerKind().set(LayerKind.APP);
                layer.getFiles().from(runnerJar);
            });
            image.addLayer(layer -> {
                layer.getLayerKind().set(LayerKind.LIBS);
                layer.getFiles().from(project.getConfigurations().getByName(RUNTIME_CLASSPATH_CONFIGURATION_NAME));
            });
            image.addLayer(layer -> {
                layer.getLayerKind().set(LayerKind.EXPANDED_RESOURCES);
                layer.getFiles().from(project.getExtensions().getByType(SourceSetContainer.class)
                        .getByName(SourceSet.MAIN_SOURCE_SET_NAME).getOutput().getResourcesDir());
            });
        });
    }

    private static String adaptTaskName(String baseName, String context) {
        if ("main".equals(context)) {
            return baseName;
        }
        return context + capitalize(baseName);
    }

    private void createDockerImage(Project project, MicronautDockerImage imageSpec) {
        TaskContainer tasks = project.getTasks();
        String imageName = imageSpec.getName();
        project.getLogger().info("Creating docker tasks for image " + imageName);
        TaskProvider<BuildLayersTask> buildLayersTask = tasks.register(adaptTaskName("buildLayers", imageName), BuildLayersTask.class, task -> {
            task.setGroup(BasePlugin.BUILD_GROUP);
            task.setDescription("Builds application layers for use in a Docker container (" + imageName + " image)");
            task.getLayers().set(imageSpec.findLayers(RuntimeKind.JIT));
            task.getOutputDir().convention(project.getLayout().getBuildDirectory().dir("docker/" + imageName + "/layers"));
        });
        TaskProvider<BuildLayersTask> buildNativeLayersTask = tasks.register(adaptTaskName("buildNativeLayersTask", imageName), BuildLayersTask.class, task -> {
            task.setGroup(BasePlugin.BUILD_GROUP);
            task.setDescription("Builds application layers for use in a Docker container (" + imageName + " image)");
            task.getLayers().set(imageSpec.findLayers(RuntimeKind.NATIVE));
            task.getOutputDir().convention(project.getLayout().getBuildDirectory().dir("docker/native-" + imageName + "/layers"));
        });

        tasks.configureEach(task -> {
            if (BasePlugin.ASSEMBLE_TASK_NAME.equals(task.getName())) {
                task.dependsOn(buildLayersTask);
            }
        });

        Optional<TaskProvider<MicronautDockerfile>> dockerFileTask = configureDockerBuild(project, tasks, buildLayersTask, imageName);
        TaskProvider<NativeImageDockerfile> nativeImageDockerFileTask = configureNativeDockerBuild(project, tasks, buildNativeLayersTask, imageName);

        project.afterEvaluate(eval -> {
            Optional<DockerBuildStrategy> buildStrategy;
            MicronautRuntime mr = PluginsHelper.resolveRuntime(project);
            if (mr != MicronautRuntime.NONE) {
                buildStrategy = Optional.of(mr.getBuildStrategy());
            } else {
                buildStrategy = Optional.empty();
            }
            nativeImageDockerFileTask.configure(it -> {
                buildStrategy.ifPresent(bs -> it.getBuildStrategy().set(buildStrategy.get()));
                it.setupNativeImageTaskPostEvaluate();
            });
            dockerFileTask.ifPresent(t -> t.configure(it -> {
                buildStrategy.ifPresent(bs -> it.getBuildStrategy().set(buildStrategy.get()));
                it.setupTaskPostEvaluate();
            }));
        });
    }

    @NotNull
    private TaskProvider<Jar> createMainRunnerJar(Project project, TaskContainer tasks) {
        return tasks.register("runnerJar", Jar.class, jar -> {
            jar.dependsOn(tasks.findByName("classes"));
            jar.getArchiveClassifier().set("runner");
            SourceSetContainer sourceSets = project
                    .getExtensions().getByType(SourceSetContainer.class);

            SourceSet mainSourceSet = sourceSets
                    .getByName(SourceSet.MAIN_SOURCE_SET_NAME);

            FileCollection dirs = mainSourceSet.getOutput().getClassesDirs();

            jar.from(dirs);
            jar.manifest(manifest -> {
                Map<String, Object> attrs = new HashMap<>(2);
                JavaApplication javaApplication = project.getExtensions().getByType(JavaApplication.class);
                attrs.put("Main-Class", javaApplication.getMainClass());
                attrs.put("Class-Path", project.getProviders().provider(() -> {
                    List<String> classpath = new ArrayList<>();
                    Configuration runtimeClasspath = project.getConfigurations()
                            .getByName(RUNTIME_CLASSPATH_CONFIGURATION_NAME);

                    for (File file : runtimeClasspath) {
                        classpath.add("libs/" + file.getName());
                    }
                    classpath.add("resources/");
                    classpath.add("classes/");
                    return String.join(" ", classpath);
                }));
                manifest.attributes(attrs);
            });
        });
    }

    private Optional<TaskProvider<MicronautDockerfile>> configureDockerBuild(Project project,
                                                                             TaskContainer tasks,
                                                                             TaskProvider<BuildLayersTask> buildLayersTask,
                                                                             String imageName) {
        File f = project.file(adaptTaskName("Dockerfile", imageName));

        TaskProvider<? extends Dockerfile> dockerFileTask;
        String dockerFileTaskName = adaptTaskName("dockerfile", imageName);
        Provider<RegularFile> targetDockerFile = project.getLayout().getBuildDirectory().file("docker/" + imageName + "/Dockerfile");
        if (f.exists()) {
            dockerFileTask = tasks.register(dockerFileTaskName, Dockerfile.class, task -> {
                task.setGroup(BasePlugin.BUILD_GROUP);
                task.setDescription("Builds a Docker File for image " + imageName);
                task.getDestFile().set(targetDockerFile);
                task.instructionsFromTemplate(f);
            });
        } else {
            dockerFileTask = tasks.register(dockerFileTaskName, MicronautDockerfile.class, task -> {
                task.setGroup(BasePlugin.BUILD_GROUP);
                task.setDescription("Builds a Docker File for image " + imageName);
                task.getDestFile().set(targetDockerFile);
                task.setupDockerfileInstructions();
            });
        }
        TaskProvider<DockerBuildImage> dockerBuildTask = tasks.register(adaptTaskName("dockerBuild", imageName), DockerBuildImage.class, task -> {
            task.dependsOn(buildLayersTask);
            task.setGroup(BasePlugin.BUILD_GROUP);
            task.setDescription("Builds a Docker Image (image " + imageName + ")");
            if (f.exists()) {
                task.getDockerFile().set(f);
            } else {
                task.getDockerFile()
                        .convention(dockerFileTask.flatMap(Dockerfile::getDestFile));
            }
            task.getImages().set(Collections.singletonList(project.getName()));
            ;
            task.getInputDir().set(dockerFileTask.flatMap(Dockerfile::getDestDir));
        });

        TaskProvider<DockerPushImage> pushDockerImage = tasks.register(adaptTaskName("dockerPush", imageName), DockerPushImage.class, task -> {
            task.dependsOn(dockerBuildTask);
            task.setGroup("upload");
            task.setDescription("Pushes the " + imageName + " Docker Image");
            task.getImages().set(dockerBuildTask.flatMap(DockerBuildImage::getImages));
        });
        if (!f.exists()) {
            return Optional.of((TaskProvider<MicronautDockerfile>) dockerFileTask);
        }
        return Optional.empty();
    }

    private TaskProvider<NativeImageDockerfile> configureNativeDockerBuild(Project project,
                                                                           TaskContainer tasks,
                                                                           TaskProvider<BuildLayersTask> buildLayersTask,
                                                                           String imageName) {
        File f = project.file(adaptTaskName("DockerfileNative", imageName));

        TaskProvider<NativeImageDockerfile> dockerFileTask;
        String dockerfileNativeTaskName = adaptTaskName("dockerfileNative", imageName);
        Provider<RegularFile> targetDockerFile = project.getLayout().getBuildDirectory().file("docker/native-" + imageName + "/DockerfileNative");
        if (f.exists()) {
            dockerFileTask = tasks.register(dockerfileNativeTaskName, NativeImageDockerfile.class, task -> {
                task.setGroup(BasePlugin.BUILD_GROUP);
                task.setDescription("Builds a Native Docker File for image " + imageName);
                task.instructionsFromTemplate(f);
                task.getDestFile().set(targetDockerFile);
            });
        } else {
            dockerFileTask = tasks.register(dockerfileNativeTaskName, NativeImageDockerfile.class, task -> {
                task.setGroup(BasePlugin.BUILD_GROUP);
                task.setDescription("Builds a Native Docker File for image " + imageName);
                task.getDestFile().set(targetDockerFile);
            });
        }
        TaskProvider<PrepareDockerContext> prepareContext = tasks.register(adaptTaskName("dockerPrepareContext", imageName), PrepareDockerContext.class, context -> {
            // Because docker requires all files to be found in the build context we need to
            // copy the configuration file directories into the build context
            context.getOutputDirectory().set(project.getLayout().getBuildDirectory().dir("docker/native-" + imageName + "/config-dirs"));
            context.getInputDirectories().from(dockerFileTask.map(t -> t.getNativeImageOptions()
                    .get()
                    .getConfigurationFileDirectories()
            ));
        });
        TaskProvider<DockerBuildImage> dockerBuildTask = tasks.register(adaptTaskName("dockerBuildNative", imageName), DockerBuildImage.class, task -> {
            task.setGroup(BasePlugin.BUILD_GROUP);
            task.setDescription("Builds a Native Docker Image using GraalVM (image " + imageName + ")");
            task.getInputs().files(prepareContext);
            if (f.exists()) {
                task.getDockerFile().set(f);
            } else {
                task.getDockerFile()
                        .convention(dockerFileTask.flatMap(Dockerfile::getDestFile));
            }
            task.getImages().set(Collections.singletonList(project.getName()));
            task.dependsOn(buildLayersTask);
            task.getInputDir().set(dockerFileTask.flatMap(Dockerfile::getDestDir));
        });

        TaskProvider<DockerPushImage> pushDockerImage = tasks.register(adaptTaskName("dockerPushNative", imageName), DockerPushImage.class);
        pushDockerImage.configure(task -> {
            task.dependsOn(dockerBuildTask);
            task.setGroup("upload");
            task.setDescription("Pushes a Native Docker Image using GraalVM (image " + imageName + ")");
            task.getImages().set(dockerBuildTask.flatMap(DockerBuildImage::getImages));
        });

        project.afterEvaluate(p -> {
            MicronautRuntime mr = PluginsHelper.resolveRuntime(p);
            if (mr == MicronautRuntime.LAMBDA) {
                TaskContainer taskContainer = p.getTasks();
                TaskProvider<DockerCreateContainer> createLambdaContainer = taskContainer.register(adaptTaskName("createLambdaContainer", imageName), DockerCreateContainer.class, task -> {
                    task.dependsOn(dockerBuildTask);
                    task.targetImageId(dockerBuildTask.flatMap(DockerBuildImage::getImageId));
                });
                TaskProvider<DockerCopyFileFromContainer> buildLambdaZip = taskContainer.register(adaptTaskName("buildNativeLambda", imageName), DockerCopyFileFromContainer.class);
                File lambdaZip = new File(project.getBuildDir(), "libs/" + project.getName() + "-" + project.getVersion() + "-lambda.zip");
                TaskProvider<DockerRemoveContainer> removeContainer = taskContainer.register(adaptTaskName("destroyLambdaContainer", imageName), DockerRemoveContainer.class);
                removeContainer.configure(task -> {
                    task.mustRunAfter(buildLambdaZip);
                    task.getContainerId().set(
                            createLambdaContainer.flatMap(DockerCreateContainer::getContainerId)
                    );
                });
                buildLambdaZip.configure(task -> {
                    task.dependsOn(createLambdaContainer);
                    task.getContainerId().set(
                            createLambdaContainer.flatMap(DockerCreateContainer::getContainerId)
                    );
                    task.getRemotePath().set("/function/function.zip");
                    task.getHostPath().set(lambdaZip.getAbsolutePath());
                    task.doLast(task1 -> System.out.println("AWS Lambda ZIP built: " + lambdaZip));
                    task.finalizedBy(removeContainer);
                });
            }

        });
        return dockerFileTask;
    }
}
