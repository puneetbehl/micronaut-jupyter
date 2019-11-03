package ai.stainless.micronaut.jupyter

import groovy.json.JsonBuilder
import groovy.util.logging.Slf4j
import io.micronaut.context.ApplicationContext
import io.micronaut.context.annotation.Context
import io.micronaut.context.annotation.Value
import io.micronaut.management.endpoint.annotation.Endpoint
import io.micronaut.runtime.server.EmbeddedServer

import javax.annotation.PostConstruct
import javax.inject.Inject
import java.nio.file.Files

/**
 * Installs/updates the Jupyter kernel when the context is created.
 */
@Slf4j
@Context
public class InstallKernel {

    @Inject
    private ApplicationContext applicationContext

    @Value('${jupyter.kernel.location:/usr/local/share/jupyter/kernels}')
    private String kernelsLocation

    @Value('${jupyter.kernel.name:Micronaut}')
    private String kernelName

    @Value('${jupyter.kernel.install:true}')
    private Boolean installKernel

    @Value('${jupyter.server-url:DEFAULT_EMBEDDED_SERVER}')
    private String serverUrl

    @PostConstruct
    public void install () {
        //if we aren't supposed to install the kernel
        if (!installKernel) {
            //then do nothing more
            log.warn "\${jupyter.kernel.install} set to false, will NOT install jupyter kernel on system!"
            return
        }
        // ensure our location exists
        File location = new File(kernelsLocation)
        try {
            location.mkdirs()
        }
        catch (e) {
            throw new RuntimeException("Unable to create kernels location at ${kernelsLocation}!", e)
        }
        // ensure that we can write to this location
        if (!Files.isWritable(location.toPath())) {
            throw new RuntimeException(
                "Unable to access kernels location at ${kernelsLocation}! (No write permissions.)"
            )
        }
        // create/update kernel json
        def kernelDirectory = getKernelDirectory()
        new FileTreeBuilder(location)."$kernelDirectory" {
            "kernel.json"("")
            "kernel.sh"("")
        }
        new File("$kernelsLocation/$kernelDirectory/kernel.json").write createKernelJson()
        File kernelSh = new File("$kernelsLocation/$kernelDirectory/kernel.sh")
        kernelSh.write createKernelSh()
        // things like the Python and Java binaries can be executed by anyone,
        // so I don't see the harm in letting this be executed by anyone as well,
        // it essentially does the same thing
        kernelSh.setExecutable(true, false)
    }

    private getKernelDirectory () {
        return kernelName.replaceAll("[^A-Za-z0-9]", "-").toLowerCase()
    }

    private getEndpointPath () {
        applicationContext
            .getBeanDefinition(KernelEndpoint)
            .getAnnotation(Endpoint)
            .stringValue("id")
            .get()
    }

    private getServerUrl () {
        //if we were given a value
        if (serverUrl != "DEFAULT_EMBEDDED_SERVER") {
            //just use the configured value
            return serverUrl
        }
        //else, we had better have an EmbeddedServer
        if (applicationContext.containsBean(EmbeddedServer)) {
            //we have an embedded server, return the url from it
            return applicationContext.getBean(EmbeddedServer).URL.toString()
        }
        //else, we can't do anything
        throw new RuntimeException(
            "\${jupyter.server-url} config not set AND no EmbeddedServer bean found. " +
                "One of these must be available for micronaut-jupyter to function."
        )
    }

    private createKernelJson () {
        return new JsonBuilder([
            language: "groovy",
            argv: [
                "$kernelsLocation/$kernelDirectory/kernel.sh",
                "{connection_file}"
            ],
            display_name: kernelName
        ]).toPrettyString()
    }

    private createKernelSh () {
        // create endpoint url to call
        def endpointUrl = "${getServerUrl()}/${getEndpointPath()}"
        return """#!/bin/bash
curl -X POST $endpointUrl -H 'Content-Type: application/json' -d "{\\"file\\":\\"\$1\\"}" --trace -
RET=\$?
if [ \$RET -ne 0 ]; then
  exit \$RET
fi

while true; do
  sleep 5
done

"""
    }

    String getKernelLocation() {
        return kernelsLocation
    }
}
