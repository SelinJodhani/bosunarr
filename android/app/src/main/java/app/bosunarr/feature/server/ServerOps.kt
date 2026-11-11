package app.bosunarr.feature.server

import app.bosunarr.data.ServiceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/*
 * What the Server screen runs. Everything here is a constant script except container ids, which are validated
 * ([containerRef]). Only the start/stop/restart actions change anything, and only on a user's tap.
 */

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

/** Common PATH additions: `sh -s` over SSH gets a minimal PATH, and Docker may live in /snap/bin or /usr/local/bin. */
private const val PATH_FIX = "PATH=\"\$PATH:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/snap/bin\"; export PATH\nexport LC_ALL=C\n"

/* ================================================================ overview */

data class CpuSample(val busy: Long, val total: Long)

/** Counters read at one moment, used to compute rates against the next read. */
data class Sample(val uptime: Double, val cpu: CpuSample?, val net: Map<String, Pair<Long, Long>>)

data class Disk(val filesystem: String, val mount: String, val total: Long, val used: Long, val available: Long)

data class NetRate(val iface: String, val rxPerSec: Double, val txPerSec: Double, val rxTotal: Long, val txTotal: Long)

data class Temperature(val label: String, val celsius: Double)

data class Overview(
    val hostname: String,
    val os: String?,
    val kernel: String?,
    val uptimeSec: Long,
    val load: List<Double>,
    val cores: Int?,
    /** 0..1, null until two samples exist. */
    val cpu: Double?,
    val memTotal: Long,
    val memAvailable: Long,
    val swapTotal: Long,
    val swapFree: Long,
    val disks: List<Disk>,
    val net: List<NetRate>,
    val temps: List<Temperature>,
    val sample: Sample,
) {
    val memUsed: Long get() = (memTotal - memAvailable).coerceAtLeast(0)
    val swapUsed: Long get() = (swapTotal - swapFree).coerceAtLeast(0)
}

private const val SAMPLE = "echo @@uptime; cat /proc/uptime\necho @@stat; head -n 1 /proc/stat\necho @@net; cat /proc/net/dev\n"

private const val OVERVIEW_HEAD = PATH_FIX + """
echo @@host; hostname 2>/dev/null || cat /proc/sys/kernel/hostname
echo @@os; cat /etc/os-release 2>/dev/null
echo @@kernel; uname -srm
echo @@load; cat /proc/loadavg
echo @@cores; nproc 2>/dev/null || grep -c ^processor /proc/cpuinfo
echo @@mem; cat /proc/meminfo
"""

private val OVERVIEW_TAIL = """
echo @@df; df -P -k -x tmpfs -x devtmpfs -x overlay -x squashfs -x efivarfs 2>/dev/null || df -P -k
echo @@temp; for z in /sys/class/thermal/thermal_zone*; do [ -r "${'$'}z/temp" ] && echo "${'$'}(cat "${'$'}z/type" 2>/dev/null) ${'$'}(cat "${'$'}z/temp")"; done 2>/dev/null
echo @@hwmon; for h in /sys/class/hwmon/hwmon*; do n=${'$'}(cat "${'$'}h/name" 2>/dev/null); for t in "${'$'}h"/temp*_input; do [ -r "${'$'}t" ] && echo "${'$'}n ${'$'}(cat "${'$'}{t%_input}_label" 2>/dev/null | tr ' ' '_') ${'$'}(cat "${'$'}t")"; done; done 2>/dev/null
echo @@end
"""

/** One read of the box's health. With no recent [previous] sample, samples CPU/network twice, 1 s apart. */
internal fun overviewScript(twoSamples: Boolean): String =
    OVERVIEW_HEAD + SAMPLE.replace("@@uptime", "@@uptime1").replace("@@stat", "@@stat1").replace("@@net", "@@net1") +
        (if (twoSamples) "sleep 1\n" + SAMPLE.replace("@@uptime", "@@uptime2").replace("@@stat", "@@stat2").replace("@@net", "@@net2") else "") +
        OVERVIEW_TAIL

internal fun sections(out: String): Map<String, List<String>> {
    val map = LinkedHashMap<String, MutableList<String>>()
    var cur: MutableList<String>? = null
    for (line in out.lineSequence()) {
        if (line.startsWith("@@")) {
            cur = mutableListOf<String>().also { map[line.removePrefix("@@").trim()] = it }
        } else cur?.add(line)
    }
    return map
}

private fun parseCpu(lines: List<String>?): CpuSample? {
    val f = lines?.firstOrNull { it.startsWith("cpu ") }?.trim()?.split(Regex("\\s+"))?.drop(1)?.mapNotNull { it.toLongOrNull() } ?: return null
    if (f.size < 4) return null
    // user nice system idle iowait irq softirq steal (guest is already counted in user)
    val total = f.take(8).sum()
    val idle = f[3] + (f.getOrNull(4) ?: 0)
    return CpuSample(total - idle, total)
}

private fun parseNet(lines: List<String>?): Map<String, Pair<Long, Long>> {
    val out = LinkedHashMap<String, Pair<Long, Long>>()
    lines?.forEach { l ->
        val i = l.indexOf(':')
        if (i <= 0) return@forEach
        val name = l.substring(0, i).trim()
        val f = l.substring(i + 1).trim().split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }
        if (f.size >= 9) out[name] = f[0] to f[8]
    }
    return out
}

private fun parseSample(s: Map<String, List<String>>, n: Int): Sample? {
    val up = s["uptime$n"]?.firstOrNull()?.trim()?.split(' ')?.firstOrNull()?.toDoubleOrNull() ?: return null
    return Sample(up, parseCpu(s["stat$n"]), parseNet(s["net$n"]))
}

/** Virtual interfaces that would only add noise (each container has a veth pair). */
private fun isNoiseIface(name: String) = name == "lo" || listOf("veth", "br-", "docker", "virbr", "cni", "flannel", "cali", "vnet", "tap", "kube").any { name.startsWith(it) }

private val NOISE_FS = setOf("tmpfs", "devtmpfs", "overlay", "shm", "udev", "none", "squashfs", "efivarfs", "nsfs", "cgroup", "cgroup2")

internal fun parseDisks(lines: List<String>?): List<Disk> {
    val seen = HashSet<String>()
    return lines.orEmpty().drop(1).mapNotNull { l ->
        val f = l.trim().split(Regex("\\s+"))
        if (f.size < 6) return@mapNotNull null
        val total = f[1].toLongOrNull() ?: return@mapNotNull null
        val used = f[2].toLongOrNull() ?: 0
        val avail = f[3].toLongOrNull() ?: 0
        val mount = f.drop(5).joinToString(" ")
        val fs = f[0]
        when {
            fs in NOISE_FS || total < 1024 -> null
            mount.startsWith("/snap/") || mount.startsWith("/run") || mount.startsWith("/dev") || mount.startsWith("/sys") || mount.startsWith("/proc") -> null
            mount.startsWith("/var/lib/docker") || mount.startsWith("/var/lib/containers") || mount == "/boot/efi" -> null
            !seen.add(fs) -> null // the same filesystem bind-mounted elsewhere
            else -> Disk(fs, mount, total * 1024, used * 1024, avail * 1024)
        }
    }
}

internal fun parseTemps(s: Map<String, List<String>>): List<Temperature> {
    fun milli(v: String?) = v?.toDoubleOrNull()?.let { if (it > 1000) it / 1000 else it }?.takeIf { it > 0 && it < 150 }
    val hw = s["hwmon"].orEmpty().mapNotNull { l ->
        val f = l.trim().split(Regex("\\s+"))
        when (f.size) {
            3 -> milli(f[2])?.let { Temperature("${f[0]} ${f[1].replace('_', ' ')}", it) }
            2 -> milli(f[1])?.let { Temperature(f[0], it) }
            else -> null
        }
    }
    val zones = s["temp"].orEmpty().mapNotNull { l ->
        val f = l.trim().split(Regex("\\s+"))
        if (f.size >= 2) milli(f.last())?.let { Temperature(f.dropLast(1).joinToString(" "), it) } else null
    }
    return (hw.ifEmpty { zones }).distinctBy { it.label }.take(10)
}

internal fun parseOverview(out: String, previous: Sample?): Overview {
    val s = sections(out)
    val first = parseSample(s, 1) ?: throw SshException("The server didn't answer like Linux (no /proc) — only Linux servers are supported")
    val second = parseSample(s, 2)
    val now = second ?: first
    val before = when {
        second != null -> first
        previous != null && first.uptime > previous.uptime && first.uptime - previous.uptime < 300 -> previous
        else -> null
    }
    val dt = before?.let { now.uptime - it.uptime }?.takeIf { it > 0.2 }
    val cpu = if (before?.cpu != null && now.cpu != null) {
        val dTotal = now.cpu.total - before.cpu.total
        if (dTotal > 0) ((now.cpu.busy - before.cpu.busy).toDouble() / dTotal).coerceIn(0.0, 1.0) else null
    } else null
    val net = now.net.filterKeys { !isNoiseIface(it) }.map { (name, v) ->
        val b = before?.net?.get(name)
        val rx = if (b != null && dt != null) ((v.first - b.first) / dt).coerceAtLeast(0.0) else 0.0
        val tx = if (b != null && dt != null) ((v.second - b.second) / dt).coerceAtLeast(0.0) else 0.0
        NetRate(name, rx, tx, v.first, v.second)
    }.filter { it.rxTotal > 0 || it.txTotal > 0 }.sortedByDescending { it.rxTotal + it.txTotal }

    val mem = s["mem"].orEmpty().associate { l ->
        val k = l.substringBefore(':').trim()
        k to (l.substringAfter(':').trim().split(' ').firstOrNull()?.toLongOrNull() ?: 0L) * 1024
    }
    val os = s["os"].orEmpty().firstOrNull { it.startsWith("PRETTY_NAME=") }?.substringAfter('=')?.trim('"', '\'')
    val load = s["load"]?.firstOrNull()?.trim()?.split(' ')?.take(3)?.mapNotNull { it.toDoubleOrNull() }.orEmpty()
    return Overview(
        hostname = s["host"]?.firstOrNull()?.trim().orEmpty().ifEmpty { "?" },
        os = os,
        kernel = s["kernel"]?.firstOrNull()?.trim(),
        uptimeSec = now.uptime.toLong(),
        load = load,
        cores = s["cores"]?.firstOrNull()?.trim()?.toIntOrNull(),
        cpu = cpu,
        memTotal = mem["MemTotal"] ?: 0,
        memAvailable = mem["MemAvailable"] ?: ((mem["MemFree"] ?: 0) + (mem["Buffers"] ?: 0) + (mem["Cached"] ?: 0)),
        swapTotal = mem["SwapTotal"] ?: 0,
        swapFree = mem["SwapFree"] ?: 0,
        disks = parseDisks(s["df"]),
        net = net,
        temps = parseTemps(s),
        sample = now,
    )
}

/* ================================================================ docker */

sealed interface DockerAccess {
    /** [prefix] is `docker` or `sudo -n docker`. */
    data class Ok(val prefix: String, val version: String?) : DockerAccess
    data object Missing : DockerAccess
    data class Denied(val detail: String, val groups: String) : DockerAccess
    data class DaemonDown(val detail: String) : DockerAccess
}

private val DOCKER_PROBE = PATH_FIX + """
if ! command -v docker >/dev/null 2>&1; then echo @@missing; exit 0; fi
if out=${'$'}(docker version --format '{{.Server.Version}}' 2>&1); then echo @@docker; echo "${'$'}out"; exit 0; fi
if sudo -n docker version --format '{{.Server.Version}}' >/dev/null 2>&1; then echo @@sudo; sudo -n docker version --format '{{.Server.Version}}'; exit 0; fi
echo @@denied; echo "${'$'}out" | tail -n 3
echo @@groups; id -nG 2>/dev/null
"""

internal fun parseDockerAccess(out: String): DockerAccess {
    val s = sections(out)
    return when {
        "missing" in s -> DockerAccess.Missing
        "docker" in s -> DockerAccess.Ok("docker", s["docker"]?.firstOrNull()?.trim()?.ifEmpty { null })
        "sudo" in s -> DockerAccess.Ok("sudo -n docker", s["sudo"]?.firstOrNull()?.trim()?.ifEmpty { null })
        else -> {
            val detail = s["denied"].orEmpty().joinToString("\n").trim()
            if (detail.contains("permission denied", true)) DockerAccess.Denied(detail, s["groups"]?.firstOrNull()?.trim().orEmpty())
            else DockerAccess.DaemonDown(detail.ifEmpty { "Docker didn't answer" })
        }
    }
}

data class Container(
    val id: String,
    val name: String,
    val image: String,
    /** running, exited, created, restarting, paused, removing, dead */
    val state: String,
    /** "Up 3 days (healthy)", "Exited (0) 2 hours ago" */
    val status: String,
    val ports: String,
    val created: String,
    val project: String?,
    val service: String?,
) {
    val running: Boolean get() = state == "running"
    val exitCode: Int? get() = Regex("""Exited \((-?\d+)\)""").find(status)?.groupValues?.get(1)?.toIntOrNull()
    val health: String? get() = Regex("""\((healthy|unhealthy|health: starting)\)""").find(status)?.groupValues?.get(1)
    val shortId: String get() = id.take(12)
}

data class ContainerStats(val cpu: String, val cpuPercent: Double?, val mem: String, val memPercent: Double?, val net: String, val block: String, val pids: String)

private fun JsonObject.s(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

private fun labelValue(labels: String, key: String): String? =
    labels.split(',').firstOrNull { it.startsWith("$key=") }?.substringAfter('=')?.ifBlank { null }

internal fun parseContainers(out: String): List<Container> = out.lineSequence().mapNotNull { line ->
    val o = runCatching { json.parseToJsonElement(line.trim()) as? JsonObject }.getOrNull() ?: return@mapNotNull null
    val status = o.s("Status")
    val state = o.s("State").lowercase().ifEmpty {
        when {
            status.startsWith("Up") && status.contains("Paused") -> "paused"
            status.startsWith("Up") -> "running"
            status.startsWith("Restarting") -> "restarting"
            status.startsWith("Created") -> "created"
            else -> "exited"
        }
    }
    val labels = o.s("Labels")
    Container(
        id = o.s("ID"),
        name = o.s("Names").split(',').first(),
        image = o.s("Image"),
        state = state,
        status = status,
        ports = o.s("Ports"),
        created = o.s("CreatedAt"),
        project = labelValue(labels, "com.docker.compose.project"),
        service = labelValue(labels, "com.docker.compose.service"),
    )
}.filter { it.id.isNotEmpty() && isValidContainerRef(it.id) }.toList()

private fun pct(v: String): Double? = v.trim().removeSuffix("%").toDoubleOrNull()

/** Stats keyed by full container id (and by name as a fallback). */
internal fun parseStats(out: String): Map<String, ContainerStats> {
    val map = HashMap<String, ContainerStats>()
    out.lineSequence().forEach { line ->
        val o = runCatching { json.parseToJsonElement(line.trim()) as? JsonObject }.getOrNull() ?: return@forEach
        val st = ContainerStats(o.s("CPUPerc"), pct(o.s("CPUPerc")), o.s("MemUsage"), pct(o.s("MemPerc")), o.s("NetIO"), o.s("BlockIO"), o.s("PIDs"))
        o.s("ID").ifEmpty { o.s("Container") }.takeIf { it.isNotEmpty() }?.let { map[it] = st }
        o.s("Name").takeIf { it.isNotEmpty() }?.let { map["name:$it"] = st }
    }
    return map
}

data class ContainerDetails(
    val startedAt: String?,
    val finishedAt: String?,
    val restartPolicy: String?,
    val restartCount: Long?,
    val health: String?,
    val mounts: List<String>,
    val networks: List<String>,
    val command: String?,
)

/** Only the fields we show: no `Config.Env`, which often holds secrets. */
private const val INSPECT_FORMAT =
    "{\"state\":{{json .State}},\"restart\":{{json .HostConfig.RestartPolicy}},\"restarts\":{{.RestartCount}},\"mounts\":{{json .Mounts}},\"networks\":{{json .NetworkSettings.Networks}},\"path\":{{json .Path}}}"

private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull?.ifBlank { null }

internal fun parseInspect(out: String): ContainerDetails? {
    val o = runCatching { json.parseToJsonElement(out.trim()) as? JsonObject }.getOrNull() ?: return null
    val state = o["state"] as? JsonObject
    val zeroTime = "0001-01-01T00:00:00Z"
    return ContainerDetails(
        startedAt = state?.get("StartedAt").str()?.takeIf { it != zeroTime },
        finishedAt = state?.get("FinishedAt").str()?.takeIf { it != zeroTime },
        restartPolicy = (o["restart"] as? JsonObject)?.get("Name").str(),
        restartCount = (o["restarts"] as? JsonPrimitive)?.longOrNull,
        health = ((state?.get("Health") as? JsonObject)?.get("Status")).str(),
        mounts = (o["mounts"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { m ->
            val mo = m as? JsonObject ?: return@mapNotNull null
            val src = mo["Name"].str() ?: mo["Source"].str() ?: return@mapNotNull null
            val dst = mo["Destination"].str() ?: return@mapNotNull null
            "$src → $dst" + if (mo["RW"]?.let { (it as? JsonPrimitive)?.contentOrNull } == "false") " (ro)" else ""
        },
        networks = (o["networks"] as? JsonObject)?.keys?.toList().orEmpty(),
        command = o["path"].str(),
    )
}

enum class ContainerAction(val verb: String, val label: String, val done: String) {
    Start("start", "Start", "started"), Stop("stop", "Stop", "stopped"), Restart("restart", "Restart", "restarted")
}

/* ================================================================ session */

/**
 * The Server screen's one SSH connection, opened on first use and reused by every tab and poll; reopened when it
 * drops or the service's settings change. Also caches how to reach Docker and the last CPU/network sample.
 */
class ServerSession {
    private val mutex = Mutex()
    @Volatile private var conn: SshConn? = null
    private var key: String? = null
    @Volatile private var docker: DockerAccess.Ok? = null
    @Volatile private var lastSample: Sample? = null

    /** The last host-key refusal, for the screen to explain and offer "trust". Cleared by a good connection. */
    val hostKeyProblem = androidx.compose.runtime.mutableStateOf<SshException?>(null)

    private fun keyOf(cfg: ServiceConfig) =
        listOf(cfg.url, cfg.altUrl, cfg.username, cfg.sshAuth, cfg.sshHostKey, cfg.sshPrivateKey.hashCode(), cfg.password.hashCode()).joinToString("|")

    private suspend fun connection(cfg: ServiceConfig): SshConn = withContext(Dispatchers.IO) { mutex.withLock { connectLocked(cfg) } }

    private suspend fun connectLocked(cfg: ServiceConfig): SshConn {
        val k = keyOf(cfg)
        conn?.takeIf { it.isConnected && key == k }?.let { return it }
        conn?.close()
        conn = null; docker = null; lastSample = null
        return try {
            Ssh.connect(cfg).also {
                conn = it; key = k
                hostKeyProblem.value = null
            }
        } catch (e: HostKeyUnknown) {
            hostKeyProblem.value = e; throw e
        } catch (e: HostKeyMismatch) {
            hostKeyProblem.value = e; throw e
        }
    }

    /** Runs [block] on the open connection; reconnects and retries once if the connection had silently died. */
    suspend fun <T> use(cfg: ServiceConfig, block: suspend (SshConn) -> T): T {
        val c = connection(cfg)
        return try {
            block(c)
        } catch (e: SshDisconnected) {
            withContext(Dispatchers.IO) { c.close() }
            block(connection(cfg))
        }
    }

    fun close() {
        val c = conn
        conn = null
        docker = null
        if (c != null) Thread { c.close() }.start() // disconnecting touches the socket: never on the main thread
    }

    /* ---------------------------------------------------------------- operations */

    suspend fun overview(cfg: ServiceConfig): Overview = use(cfg) { c ->
        val prev = lastSample
        val r = c.script(overviewScript(twoSamples = prev == null), timeoutMs = 20_000)
        parseOverview(r.out, prev).also { lastSample = it.sample }
    }

    suspend fun dockerAccess(cfg: ServiceConfig): DockerAccess {
        docker?.let { return it }
        val a = use(cfg) { c -> parseDockerAccess(c.script(DOCKER_PROBE, timeoutMs = 20_000).out) }
        if (a is DockerAccess.Ok) docker = a
        return a
    }

    private suspend fun docker(cfg: ServiceConfig): DockerAccess.Ok =
        dockerAccess(cfg) as? DockerAccess.Ok ?: throw SshException("Docker isn't available to this user")

    suspend fun containers(cfg: ServiceConfig): List<Container> {
        val d = docker(cfg)
        val r = use(cfg) { it.script(PATH_FIX + "${d.prefix} ps -a --no-trunc --format '{{json .}}'\n", timeoutMs = 30_000) }
        if (!r.ok) throw SshException(dockerError(r))
        return parseContainers(r.out)
    }

    suspend fun stats(cfg: ServiceConfig): Map<String, ContainerStats> {
        val d = docker(cfg)
        val r = use(cfg) { it.script(PATH_FIX + "${d.prefix} stats --no-stream --no-trunc --format '{{json .}}'\n", timeoutMs = 45_000) }
        if (!r.ok) throw SshException(dockerError(r))
        return parseStats(r.out)
    }

    suspend fun inspect(cfg: ServiceConfig, id: String): ContainerDetails? {
        val d = docker(cfg)
        val r = use(cfg) { it.script(PATH_FIX + "${d.prefix} inspect --format '$INSPECT_FORMAT' ${containerRef(id)}\n", timeoutMs = 20_000) }
        if (!r.ok) throw SshException(dockerError(r))
        return parseInspect(r.out)
    }

    suspend fun action(cfg: ServiceConfig, id: String, action: ContainerAction) {
        val d = docker(cfg)
        val r = use(cfg) { it.script(PATH_FIX + "${d.prefix} ${action.verb} ${containerRef(id)}\n", timeoutMs = 90_000) }
        if (!r.ok) throw SshException(dockerError(r))
    }

    suspend fun logs(cfg: ServiceConfig, id: String, tail: Int = 200): String {
        val d = docker(cfg)
        val r = use(cfg) { it.script(PATH_FIX + "${d.prefix} logs --tail ${tail.coerceIn(1, 5000)} ${containerRef(id)} 2>&1\n", timeoutMs = 30_000, maxBytes = 2 shl 20) }
        if (!r.ok && r.out.isBlank()) throw SshException(dockerError(r))
        return r.out
    }

    /**
     * Follows a container's logs. Runs through `sh -c '…'` (the id is validated, so it can't break out of the
     * quotes) on a terminal, so leaving the viewer hangs the remote `docker logs -f` up.
     */
    suspend fun followLogs(cfg: ServiceConfig, id: String, tail: Int = 200): Flow<String> {
        val d = docker(cfg)
        if (!isValidContainerRef(id)) throw SshException("Unexpected container id")
        val c = connection(cfg)
        return c.lines("sh -c 'PATH=\$PATH:/usr/local/bin:/usr/bin:/snap/bin; exec ${d.prefix} logs --tail ${tail.coerceIn(1, 5000)} -f $id 2>&1'", pty = true)
    }

    /** A user's saved command, through their login shell, exactly as typed. */
    suspend fun runCommand(cfg: ServiceConfig, command: String): ExecResult =
        use(cfg) { it.exec(command, timeoutMs = 120_000, maxBytes = 1 shl 20) }

    private fun dockerError(r: ExecResult): String {
        val e = (r.err.ifBlank { r.out }).trim().lineSequence().lastOrNull { it.isNotBlank() }.orEmpty()
        return when {
            e.contains("permission denied", true) -> "Docker refused: permission denied"
            e.contains("No such container", true) -> "That container no longer exists"
            e.isNotEmpty() -> e.take(200)
            else -> "Docker failed (exit ${r.exit})"
        }
    }
}

/* ================================================================ probes */

data class ServerTestResult(val uname: String, val ms: Int)

object ServerProbe {
    /** "Test" in the setup form: connect (host-key checks included), run `uname -a`. */
    suspend fun test(cfg: ServiceConfig): ServerTestResult {
        val started = System.nanoTime()
        val c = Ssh.connect(cfg)
        try {
            val r = c.exec("uname -a", timeoutMs = 15_000)
            val ms = ((System.nanoTime() - started) / 1_000_000).toInt()
            val text = r.out.trim().ifEmpty { r.err.trim() }.ifEmpty { "Connected (uname printed nothing)" }
            return ServerTestResult(text.take(300), ms)
        } finally {
            withContext(Dispatchers.IO) { c.close() }
        }
    }

    /** Reachability for the dashboard/Settings status dot: connect + authenticate, nothing else. Needs a pinned key. */
    suspend fun status(cfg: ServiceConfig): Int {
        if (cfg.sshHostKey.isNullOrBlank()) throw SshException("Host key not verified yet — open the server's settings and tap Test")
        val started = System.nanoTime()
        val c = Ssh.connect(cfg, timeoutMs = 8_000)
        withContext(Dispatchers.IO) { c.close() }
        return ((System.nanoTime() - started) / 1_000_000).toInt()
    }
}
