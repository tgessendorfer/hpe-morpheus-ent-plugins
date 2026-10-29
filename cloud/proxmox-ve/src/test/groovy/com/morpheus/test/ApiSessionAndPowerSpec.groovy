package com.morpheus.test

import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.Workload
import com.morpheusdata.proxmox.ve.ProxmoxVeCloudProvider
import com.morpheusdata.proxmox.ve.ProxmoxVePlugin
import com.morpheusdata.proxmox.ve.ProxmoxVeProvisionProvider
import com.morpheusdata.proxmox.ve.sync.DatastoreSync
import com.morpheusdata.proxmox.ve.sync.HostSync
import com.morpheusdata.proxmox.ve.sync.LxcSync
import com.morpheusdata.proxmox.ve.sync.NetworkSync
import com.morpheusdata.proxmox.ve.sync.PoolSync
import com.morpheusdata.proxmox.ve.sync.VMSync
import com.morpheusdata.proxmox.ve.sync.VirtualImageLocationSync
import com.morpheusdata.proxmox.ve.util.ProxmoxApiComputeUtil
import com.morpheusdata.response.ServiceResponse
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import spock.lang.Specification

class ApiSessionAndPowerSpec extends Specification {

    static final String UPID = 'UPID:pve1:0001A2B3:0C4D5E6F:6720A1B2:qmshutdown:101:api@pve:'

    /**
     * Answers the plugin's API calls from a script, without a network, and records each request:
     * path, method, body and the request options that matter here.
     */
    static class ScriptedClient extends HttpApiClient {
        List<Map> calls = []
        Closure<Map> answer = { String method, String path, def body -> null }

        @Override
        ServiceResponse callJsonApi(String url, String path, String username, String password, HttpApiClient.RequestOptions opts, String method) {
            return reply(path, opts, method)
        }

        @Override
        ServiceResponse callJsonApi(String url, String path, HttpApiClient.RequestOptions opts, String method) {
            return reply(path, opts, method)
        }

        private ServiceResponse reply(String path, HttpApiClient.RequestOptions opts, String method) {
            calls << [path: path, method: method, body: opts?.body, ignoreSSL: opts?.ignoreSSL,
                      suppressLog: opts?.suppressLog, cookie: opts?.headers?.get('Cookie')?.toString()]
            Map scripted = answer.call(method, path, opts?.body) ?: [status: 404, body: [data: null, message: 'not scripted']]
            String content = JsonOutput.toJson(scripted.body)
            boolean ok = (scripted.status ?: 200) < 300
            ServiceResponse rtn = new ServiceResponse(success: ok, content: content, data: new JsonSlurper().parseText(content))
            if (!ok) {
                rtn.errorCode = scripted.status.toString()
            }
            if (scripted.error) {
                rtn.error = scripted.error
            }
            return rtn
        }

        List<String> paths() {
            calls*.path
        }
    }

    static Map job(Map extra = [:]) {
        return [apiUrl: 'https://proxmox.example.com:8006', v2basePath: '/api2/json', username: 'api@pve', password: 'secret',
                verifyTls: false] + extra
    }

    /** A job that already holds a ticket, so no request below logs in. */
    static Map loggedIn(Map extra = [:]) {
        Map authConfig = job(extra)
        authConfig[ProxmoxApiComputeUtil.TICKET_KEY] = [token: 'PVE:api@pve:TICKET', csrfToken: 'CSRF',
                                                       obtainedAt: System.currentTimeMillis()]
        return authConfig
    }

    static Map loginAnswer = [body: [data: [ticket: 'PVE:api@pve:NEW', CSRFPreventionToken: 'NEWCSRF']]]

    static Map taskAnswer(String exitstatus) {
        return [body: [data: [status: 'stopped', exitstatus: exitstatus, upid: UPID]]]
    }

    static Map statusAnswer(String status) {
        return [body: [data: [status: status, vmid: 101]]]
    }

    // ---- one login per job ----

    def "a login keeps the ticket in the job's authConfig, and the job's later calls reuse it"() {
        given:
        def client = new ScriptedClient(answer: { m, p, b -> p.endsWith('/access/ticket') ? loginAnswer : statusAnswer('running') })
        Map authConfig = job()

        when:
        def first = ProxmoxApiComputeUtil.login(client, authConfig)
        def again = ProxmoxApiComputeUtil.getApiV2Token(authConfig)
        def status = ProxmoxApiComputeUtil.getVMStatus(client, authConfig, 'pve1', '101')
        ProxmoxApiComputeUtil.getVMStatus(client, authConfig, 'pve1', '101')

        then:
        first.success
        first.data == [csrfToken: 'NEWCSRF', token: 'PVE:api@pve:NEW']
        authConfig[ProxmoxApiComputeUtil.TICKET_KEY].token == 'PVE:api@pve:NEW'
        again.success
        again.data == first.data
        status == 'running'
        client.paths().count { it.endsWith('/access/ticket') } == 1
        client.calls.findAll { !it.path.endsWith('/access/ticket') }*.cookie.every { it == 'PVEAuthCookie=PVE:api@pve:NEW' }
    }

    def "a ticket is reused for an hour and no longer"() {
        given:
        long now = System.currentTimeMillis()
        Map authConfig = job()
        authConfig[ProxmoxApiComputeUtil.TICKET_KEY] = [token: 'PVE:t', csrfToken: 'c', obtainedAt: now]

        expect:
        ProxmoxApiComputeUtil.cachedTicket(authConfig, now) == [csrfToken: 'c', token: 'PVE:t']
        ProxmoxApiComputeUtil.cachedTicket(authConfig, now + 59 * 60 * 1000L) == [csrfToken: 'c', token: 'PVE:t']
        ProxmoxApiComputeUtil.cachedTicket(authConfig, now + 60 * 60 * 1000L) == null
        ProxmoxApiComputeUtil.cachedTicket(authConfig, now + 61 * 60 * 1000L) == null
        ProxmoxApiComputeUtil.cachedTicket(job(), now) == null
        ProxmoxApiComputeUtil.cachedTicket(null, now) == null
        ProxmoxApiComputeUtil.cachedTicket(job([(ProxmoxApiComputeUtil.TICKET_KEY): [token: 'PVE:t']]), now) == null
    }

    def "a failed login keeps nothing"() {
        given:
        def client = new ScriptedClient(answer: { m, p, b -> [status: 401, body: [data: null]] })
        Map authConfig = job()

        when:
        def result = ProxmoxApiComputeUtil.login(client, authConfig)

        then:
        !result.success
        !authConfig.containsKey(ProxmoxApiComputeUtil.TICKET_KEY)
        result.msg.startsWith('Error retrieving token')
    }

    def "the login response is kept out of the log"() {
        given:
        def client = new ScriptedClient(answer: { m, p, b -> loginAnswer })

        when:
        ProxmoxApiComputeUtil.login(client, job())

        then:
        client.calls.size() == 1
        client.calls[0].method == 'POST'
        client.calls[0].suppressLog == true
    }

    def "the refresh's authConfig reaches every sync, so the syncs share its login"() {
        given:
        def plugin = new ProxmoxVePlugin()
        def cloud = new Cloud(serviceUrl: 'https://proxmox.example.com:8006', accountCredentialLoaded: true)
        def client = new ScriptedClient()
        Map authConfig = loggedIn()

        expect:
        new PoolSync(plugin, cloud, client, authConfig).@authConfig.is(authConfig)
        new HostSync(plugin, cloud, client, authConfig).@authConfig.is(authConfig)
        new DatastoreSync(plugin, cloud, client, authConfig).@authConfig.is(authConfig)
        new NetworkSync(plugin, cloud, client, authConfig).@authConfig.is(authConfig)
        new VMSync(plugin, cloud, client, null, authConfig).@authConfig.is(authConfig)
        new LxcSync(plugin, cloud, client, null, authConfig).@authConfig.is(authConfig)
        new VirtualImageLocationSync(plugin, cloud, client, null, authConfig).@authConfig.is(authConfig)
        // Without one, a sync builds its own from the cloud, as before
        new PoolSync(plugin, cloud, client).@authConfig.apiUrl == 'https://proxmox.example.com:8006'
    }

    // ---- Verify TLS Certificate ----

    def "the cloud form offers Verify TLS Certificate, unticked by default"() {
        when:
        OptionType option = new ProxmoxVeCloudProvider(null, null).getOptionTypes().find { it.code == 'proxmox-verify-tls' }

        then:
        option != null
        option.inputType == OptionType.InputType.CHECKBOX
        option.fieldContext == 'config'
        option.fieldName == 'verifyTls'
        option.required == false
        !ProxmoxApiComputeUtil.checkboxTicked(option.defaultValue)
        option.helpText
    }

    def "the checkbox reaches the job's authConfig"() {
        given:
        def plugin = new ProxmoxVePlugin()
        def cloud = new Cloud(serviceUrl: 'https://proxmox.example.com:8006', accountCredentialLoaded: true, config: config)

        expect:
        plugin.getAuthConfig(cloud).verifyTls == expected
        ProxmoxApiComputeUtil.ignoreSsl(plugin.getAuthConfig(cloud)) == !expected

        where:
        config                     || expected
        '{"verifyTls":"on"}'       || true
        '{"verifyTls":"off"}'      || false
        '{"verifyTls":""}'         || false
        '{}'                       || false
        null                       || false
    }

    def "a ticked checkbox reads as ticked"() {
        expect:
        ProxmoxApiComputeUtil.checkboxTicked(raw) == expected

        where:
        raw     || expected
        'on'    || true
        'true'  || true
        true    || true
        '1'     || true
        'off'   || false
        ''      || false
        null    || false
        false   || false
    }

    def "every request of a job, the login included, carries the job's TLS mode"() {
        given:
        def client = new ScriptedClient(answer: { m, p, b ->
            if (p.endsWith('/access/ticket')) return loginAnswer
            if (p.contains('/tasks/')) return taskAnswer('OK')
            if (p.endsWith('/status/current')) return statusAnswer('running')
            return [body: [data: UPID]]
        })
        Map authConfig = job(verifyTls: verify)

        when:
        ProxmoxApiComputeUtil.login(client, authConfig)
        ProxmoxApiComputeUtil.callListApiV2(client, 'cluster/resources?type=vm', authConfig)
        ProxmoxApiComputeUtil.shutdownVMAndWait(client, authConfig, 'pve1', '101')
        ProxmoxApiComputeUtil.destroyVM(client, authConfig, 'pve1', '101')

        then:
        client.calls.size() == 5
        client.calls*.ignoreSSL.every { it == !verify }

        where:
        verify << [true, false]
    }

    def "an untrusted certificate at login says what to do"() {
        given:
        def client = new ScriptedClient(answer: { m, p, b ->
            [status: 500, body: [data: null], error: 'https://proxmox.example.com:8006/api2/json/access/ticket : PKIX path building failed: ' +
                    'sun.security.provider.certpath.SunCertPathBuilderException: unable to find valid certification path to requested target']
        })

        when:
        def verified = ProxmoxApiComputeUtil.login(client, job(verifyTls: true))
        def unverified = ProxmoxApiComputeUtil.login(client, job(verifyTls: false))

        then:
        !verified.success
        verified.msg == ProxmoxApiComputeUtil.CERTIFICATE_NOT_TRUSTED_MSG
        !unverified.success
        unverified.msg != ProxmoxApiComputeUtil.CERTIFICATE_NOT_TRUSTED_MSG
    }

    def "certificate failures are told apart from other login failures"() {
        expect:
        ProxmoxApiComputeUtil.certificateFailure(text) == expected

        where:
        text                                                                                         || expected
        'PKIX path building failed: unable to find valid certification path to requested target'    || true
        'PKIX path validation failed: CertPathValidatorException: validity check failed'             || true
        "Certificate for <proxmox.example.com> doesn't match any of the subject alternative names"   || true
        'javax.net.ssl.SSLHandshakeException: Received fatal alert: certificate_unknown'             || true
        '{"data":null}'                                                                              || false
        'Connect to proxmox.example.com:8006 failed: Connection refused'                              || false
        ''                                                                                           || false
        null                                                                                         || false
    }

    // ---- Stop, restart, delete ----

    def "Stop shuts the guest down, forced off by Proxmox after the timeout, and waits for the task"() {
        given:
        def client = new ScriptedClient(answer: { m, p, b ->
            if (p.endsWith('/status/shutdown/')) return [body: [data: UPID]]
            if (p.endsWith("/tasks/$UPID/status")) return taskAnswer('OK')
            return null
        })

        when:
        def result = ProxmoxApiComputeUtil.shutdownVMAndWait(client, loggedIn(), 'pve1', '101')

        then:
        result.success
        client.calls.size() == 2
        client.calls[0].method == 'POST'
        client.calls[0].path == '/api2/json/nodes/pve1/qemu/101/status/shutdown/'
        client.calls[0].body == [vmid: '101', node: 'pve1', forceStop: 1, timeout: ProxmoxApiComputeUtil.GUEST_SHUTDOWN_TIMEOUT_SEC]
        ProxmoxApiComputeUtil.GUEST_SHUTDOWN_TIMEOUT_SEC == 120L
        client.calls[1].method == 'GET'
        client.calls[1].path == "/api2/json/nodes/pve1/tasks/$UPID/status".toString()
        client.paths().every { !it.contains('?') }
    }

    def "a shutdown task that fails reports Proxmox's message"() {
        given:
        def client = new ScriptedClient(answer: { m, p, b ->
            p.contains('/tasks/') ? taskAnswer('VM quit/powerdown failed - got timeout') : [body: [data: UPID]]
        })

        when:
        def result = ProxmoxApiComputeUtil.shutdownVMAndWait(client, loggedIn(), 'pve1', '101')

        then:
        !result.success
        result.msg.contains('VM quit/powerdown failed - got timeout')
        // ServiceResponse.error() alone leaves msg empty; both must carry the text
        result.error == result.msg
    }

    def "a power action Proxmox refuses reports Proxmox's message"() {
        given:
        def refused = new ServiceResponse(success: false, errorCode: '500', content: '{"data":null,"message":"VM 101 is locked (backup)\\n"}')

        when:
        def result = ProxmoxApiComputeUtil.waitForPowerTask(new ScriptedClient(), loggedIn(), 'pve1', '101', 'shutdown', refused)

        then:
        !result.success
        result.msg.contains('VM 101 is locked (backup)')
    }

    def "Restart is a real reboot with the timeout, waited for"() {
        given:
        def client = new ScriptedClient(answer: { m, p, b ->
            if (p.endsWith('/status/current')) return statusAnswer('running')
            if (p.endsWith('/status/reboot/')) return [body: [data: UPID]]
            if (p.contains('/tasks/')) return taskAnswer('OK')
            return null
        })

        when:
        def result = ProxmoxApiComputeUtil.rebootVMAndWait(client, loggedIn(), 'pve1', '101')

        then:
        result.success
        client.paths().any { it.endsWith('/status/reboot/') }
        client.calls.find { it.path.endsWith('/status/reboot/') }.body.timeout == 120L
        !client.paths().any { it.endsWith('/status/reset/') || it.endsWith('/status/stop/') }
    }

    def "a reboot that does not complete falls back to a reset"() {
        given:
        int taskPolls = 0
        def client = new ScriptedClient(answer: { m, p, b ->
            if (p.endsWith('/status/current')) return statusAnswer('running')
            if (p.endsWith('/status/reboot/') || p.endsWith('/status/reset/')) return [body: [data: UPID]]
            if (p.contains('/tasks/')) return (taskPolls++ == 0) ? taskAnswer('VM quit/powerdown failed - got timeout') : taskAnswer('OK')
            return null
        })

        when:
        def result = ProxmoxApiComputeUtil.rebootVMAndWait(client, loggedIn(), 'pve1', '101')

        then:
        result.success
        client.paths().findAll { it.contains('/status/') && !it.endsWith('/status/current') && !it.contains('/tasks/') } ==
                ['/api2/json/nodes/pve1/qemu/101/status/reboot/', '/api2/json/nodes/pve1/qemu/101/status/reset/']
    }

    def "a failed reset after a failed reboot reports both"() {
        given:
        def client = new ScriptedClient(answer: { m, p, b ->
            if (p.endsWith('/status/current')) return statusAnswer('running')
            if (p.contains('/tasks/')) return taskAnswer('VM quit/powerdown failed - got timeout')
            return [body: [data: UPID]]
        })

        when:
        def result = ProxmoxApiComputeUtil.rebootVMAndWait(client, loggedIn(), 'pve1', '101')

        then:
        !result.success
        result.msg.contains('reboot')
        result.msg.contains('reset')
    }

    def "a reboot Proxmox #why is not followed by a reset and reports why"() {
        given:
        def client = new ScriptedClient(answer: { m, p, b ->
            if (p.endsWith('/status/current')) return statusAnswer('running')
            if (p.endsWith('/status/reboot/')) return rebootAnswer
            return [body: [data: UPID]]
        })

        when:
        def result = ProxmoxApiComputeUtil.rebootVMAndWait(client, loggedIn(), 'pve1', '101')

        then:
        !result.success
        result.msg.contains(expected)
        client.paths().any { it.endsWith('/status/reboot/') }
        !client.paths().any { it.endsWith('/status/reset/') || it.endsWith('/status/stop/') || it.contains('/tasks/') }

        where:
        why                       | rebootAnswer                                                              || expected
        'refuses'                 | [status: 500, body: [data: null, message: 'VM 101 is locked (backup)\n']] || 'VM 101 is locked (backup)'
        'accepts without a task'  | [body: [data: null]]                                                      || 'returned no task'
    }

    def "Restart starts a stopped VM"() {
        given:
        def client = new ScriptedClient(answer: { m, p, b ->
            p.endsWith('/status/current') ? statusAnswer('stopped') : [body: [data: UPID]]
        })

        when:
        def result = ProxmoxApiComputeUtil.rebootVMAndWait(client, loggedIn(), 'pve1', '101')

        then:
        result.success
        client.paths() == ['/api2/json/nodes/pve1/qemu/101/status/current', '/api2/json/nodes/pve1/qemu/101/status/start/']
    }

    def "before a delete a running VM is shut down and the task waited for"() {
        given:
        def client = new ScriptedClient(answer: { m, p, b ->
            if (p.endsWith('/status/current')) return statusAnswer('running')
            if (p.contains('/tasks/')) return taskAnswer('OK')
            return [body: [data: UPID]]
        })

        when:
        def result = ProxmoxApiComputeUtil.powerOffForDelete(client, loggedIn(), 'pve1', '101')

        then:
        result.success
        client.paths().any { it.endsWith('/status/shutdown/') }
        !client.paths().any { it.endsWith('/status/stop/') }
    }

    def "a delete whose shutdown fails stops the VM hard and waits again"() {
        given:
        int taskPolls = 0
        def client = new ScriptedClient(answer: { m, p, b ->
            if (p.endsWith('/status/current')) return statusAnswer('running')
            if (p.contains('/tasks/')) return (taskPolls++ == 0) ? taskAnswer('shutdown failed') : taskAnswer('OK')
            return [body: [data: UPID]]
        })

        when:
        def result = ProxmoxApiComputeUtil.powerOffForDelete(client, loggedIn(), 'pve1', '101')

        then:
        result.success
        client.calls.find { it.path.endsWith('/status/stop/') }.body == [vmid: '101', node: 'pve1']
        client.paths().count { it.contains('/tasks/') } == 2
    }

    def "a delete leaves a stopped VM alone"() {
        given:
        def client = new ScriptedClient(answer: { m, p, b -> statusAnswer('stopped') })

        when:
        def result = ProxmoxApiComputeUtil.powerOffForDelete(client, loggedIn(), 'pve1', '101')

        then:
        result.success
        client.paths() == ['/api2/json/nodes/pve1/qemu/101/status/current']
    }

    def "the task id comes from Proxmox's answer"() {
        expect:
        ProxmoxApiComputeUtil.taskUpid(new ServiceResponse(success: true, data: [data: UPID])) == UPID
        ProxmoxApiComputeUtil.taskUpid(new ServiceResponse(success: true, content: JsonOutput.toJson([data: UPID]))) == UPID
        ProxmoxApiComputeUtil.taskUpid(new ServiceResponse(success: true, data: [data: null])) == null
        ProxmoxApiComputeUtil.taskUpid(new ServiceResponse(success: true, content: 'not json')) == null
        ProxmoxApiComputeUtil.taskUpid(null) == null
    }

    def "Proxmox's message is read out of a refused call"() {
        expect:
        ProxmoxApiComputeUtil.apiErrorMessage(new ServiceResponse(success: false, content: '{"data":null,"message":"VM 101 not running\\n"}')) == 'VM 101 not running'
        ProxmoxApiComputeUtil.apiErrorMessage(new ServiceResponse(success: false, content: '{"data":null}', msg: 'refused')) == 'refused'
        ProxmoxApiComputeUtil.apiErrorMessage(new ServiceResponse(success: false, errorCode: '500')) == 'HTTP 500'
        ProxmoxApiComputeUtil.apiErrorMessage(null) == 'no response'
    }

    // ---- what the provisioning provider's power actions call ----

    /** A plugin whose jobs already hold a ticket, so no request below logs in. */
    static class LoggedInPlugin extends ProxmoxVePlugin {
        @Override
        def getAuthConfig(Cloud cloud) {
            return ApiSessionAndPowerSpec.loggedIn()
        }
    }

    /** The provisioning provider with every client of its power actions answering from a script. */
    static class ScriptedProvider extends ProxmoxVeProvisionProvider {
        HttpApiClient scripted

        ScriptedProvider(HttpApiClient scripted) {
            super(new LoggedInPlugin(), null)
            this.scripted = scripted
        }

        @Override
        protected HttpApiClient newApiClient() {
            return scripted
        }
    }

    /** VM 101 on node pve1, provisioned without a stored uuid, so the identity check calls nothing. */
    static ComputeServer vm() {
        return new ComputeServer(name: 'web-1', externalId: '101', cloud: new Cloud(),
                parentServer: new ComputeServer(name: 'pve1', externalId: 'pve1'))
    }

    static List<String> requests(ScriptedClient client) {
        return client.calls.collect { "${it.method} ${it.path}".toString() }
    }

    def "#action goes through the guest shutdown, never the hard stop"() {
        given:
        def client = new ScriptedClient(answer: { m, p, b ->
            if (p.endsWith('/status/shutdown/')) return [body: [data: UPID]]
            if (p.contains('/tasks/')) return taskAnswer('OK')
            return null
        })
        def provider = new ScriptedProvider(client)

        when:
        ServiceResponse result = stop.call(provider, vm())

        then:
        result.success
        requests(client) == ['POST /api2/json/nodes/pve1/qemu/101/status/shutdown/',
                             "GET /api2/json/nodes/pve1/tasks/$UPID/status".toString()]
        client.calls[0].body.forceStop == 1
        client.calls[0].body.timeout == ProxmoxApiComputeUtil.GUEST_SHUTDOWN_TIMEOUT_SEC

        where:
        action         | stop
        'stopWorkload' | { ProxmoxVeProvisionProvider p, ComputeServer s -> p.stopWorkload(new Workload(server: s)) }
        'stopServer'   | { ProxmoxVeProvisionProvider p, ComputeServer s -> p.stopServer(s) }
    }

    def "restartWorkload reboots the guest and waits for it, without a stop or a start"() {
        given:
        def client = new ScriptedClient(answer: { m, p, b ->
            if (p.endsWith('/status/current')) return statusAnswer('running')
            if (p.endsWith('/status/reboot/')) return [body: [data: UPID]]
            if (p.contains('/tasks/')) return taskAnswer('OK')
            return null
        })

        when:
        ServiceResponse result = new ScriptedProvider(client).restartWorkload(new Workload(server: vm()))

        then:
        result.success
        requests(client) == ['GET /api2/json/nodes/pve1/qemu/101/status/current',
                             'POST /api2/json/nodes/pve1/qemu/101/status/reboot/',
                             "GET /api2/json/nodes/pve1/tasks/$UPID/status".toString()]
        client.calls[1].body.timeout == ProxmoxApiComputeUtil.GUEST_SHUTDOWN_TIMEOUT_SEC
    }

    def "removeWorkload shuts the VM down, stops it hard only when the shutdown fails, and then destroys it: #shutdownExit"() {
        given:
        int taskPolls = 0
        def client = new ScriptedClient(answer: { m, p, b ->
            if (p.endsWith('/status/current')) return statusAnswer('running')
            if (p.contains('/tasks/')) return (taskPolls++ == 0) ? taskAnswer(shutdownExit) : taskAnswer('OK')
            return [body: [data: UPID]]
        })
        String task = "GET /api2/json/nodes/pve1/tasks/$UPID/status".toString()

        when:
        ServiceResponse result = new ScriptedProvider(client).removeWorkload(new Workload(server: vm()), [:])

        then:
        result.success
        requests(client) == ['GET /api2/json/nodes/pve1/qemu/101/status/current',
                             'POST /api2/json/nodes/pve1/qemu/101/status/shutdown/', task] +
                (hardStop ? ['POST /api2/json/nodes/pve1/qemu/101/status/stop/', task] : []) +
                ['DELETE /api2/json/nodes/pve1/qemu/101/']

        where:
        shutdownExit                             || hardStop
        'OK'                                     || false
        'VM quit/powerdown failed - got timeout' || true
    }
}
