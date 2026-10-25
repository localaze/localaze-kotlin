import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project
import org.gradle.api.publish.Publication
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.plugins.signing.SigningExtension
import java.io.File

/**
 * What Maven Central requires of every POM, in one place for all three
 * artifacts: a name, a description, the project URL, the licence, a developer
 * and the source repository. android/build.gradle.kts repeats it, because a
 * separate build cannot see this buildSrc; keep the two in step.
 */
fun MavenPublication.localazePom(title: String, about: String) {
    pom {
        name.set(title)
        description.set(about)
        url.set("https://github.com/localaze/localaze-kotlin")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("sharabiddin")
                name.set("Sharabiddin Ahmayev")
                url.set("https://github.com/sharabiddin")
            }
        }
        scm {
            url.set("https://github.com/localaze/localaze-kotlin")
            connection.set("scm:git:https://github.com/localaze/localaze-kotlin.git")
            developerConnection.set("scm:git:https://github.com/localaze/localaze-kotlin.git")
        }
        issueManagement {
            system.set("GitHub")
            url.set("https://github.com/localaze/localaze-kotlin/issues")
        }
    }
}

/**
 * The folder every artifact is published to before upload. The Android build
 * passes the repository root, one folder above its own.
 */
fun localazeStagingDir(repositoryRoot: File): File =
    System.getenv("LOCALAZE_STAGING_DIR")?.let(::File) ?: repositoryRoot.resolve("build/staging-deploy")

/**
 * Signs every publication when a key is in the environment, as it is in the
 * release job, and not otherwise, so a local `publishToMavenLocal` needs none.
 * The key is ASCII-armoured and never written to disk.
 */
fun Project.localazeSigning(publications: NamedDomainObjectContainer<Publication>) {
    val key = System.getenv("LOCALAZE_SIGNING_KEY") ?: return
    extensions.configure(SigningExtension::class.java) {
        useInMemoryPgpKeys(key, System.getenv("LOCALAZE_SIGNING_PASSWORD") ?: "")
        sign(publications)
    }
}
