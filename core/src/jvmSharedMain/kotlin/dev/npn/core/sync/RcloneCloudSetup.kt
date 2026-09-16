package dev.npn.core.sync

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * [CloudSetup] over rclone's remote-control API. It drives the same long-lived engine the transfers
 * use, so the wizard's questions and the eventual sign-in happen in the process that will hold the
 * remote afterwards.
 *
 * The `config/create` protocol is a state machine the caller cannot reproduce alone: every answer
 * must repeat the remote's name, type and accumulated parameters, and rclone identifies the
 * conversation by the opaque `State` it returned last. This class therefore remembers each in-flight
 * remote (name -> type + parameters) between [begin] and [answer]; a caller only ever holds the
 * [SetupStep] the UI is rendering.
 *
 * Failures rclone reports through the transport ([RcloneException]) surface as [SetupStep.Failed]
 * when a step is expected and as null from [oauthStatus]; the catalog and folder picker let a
 * failed call reach their caller, which can tell the user the engine is down.
 */
class RcloneCloudSetup(
    private val rpc: suspend () -> RcloneRpc,
) : CloudSetup {
    private val sessions = HashMap<String, Session>()

    override suspend fun providers(): List<RcloneProvider> =
        RcloneSetupWire.parseProviders(rpc().call("config/providers"))

    override suspend fun begin(
        name: String,
        type: String,
        params: Map<String, String>,
    ): SetupStep {
        val session = Session(type = type, parameters = params)
        sessions[name] = session
        return create(name, session, state = null, result = null)
    }

    override suspend fun answer(
        name: String,
        state: String,
        result: String,
    ): SetupStep {
        val session =
            sessions[name]
                ?: return SetupStep.Failed("No remote setup is in progress for '$name'.")
        return create(name, session, state = state, result = result)
    }

    override suspend fun oauthStatus(): OAuthStatus? =
        try {
            RcloneSetupWire.parseOAuthStatus(rpc().call("config/oauthstatus"))
        } catch (_: RcloneException) {
            // The engine is not answering this poll; the wizard keeps waiting.
            null
        }

    override suspend fun stopOauth() {
        try {
            rpc().call("config/oauthstop")
        } catch (_: RcloneException) {
            // "no OAuth in progress" is the normal outcome of backing out after sign-in finished.
        }
    }

    override suspend fun folders(
        remote: String,
        path: String,
    ): List<String> {
        val input =
            buildJsonObject {
                put("fs", remote)
                put("remote", path)
                putJsonObject("opt") {
                    put("filesOnly", false)
                    put("dirsOnly", true)
                    put("noModTime", true)
                    put("noMimeType", true)
                }
            }
        return RcloneSetupWire.parseFolderNames(rpc().call("operations/list", input), path)
    }

    override suspend fun makeFolder(
        remote: String,
        path: String,
    ) {
        rpc().call(
            "operations/mkdir",
            buildJsonObject {
                put("fs", remote)
                put("remote", path)
            },
        )
    }

    private suspend fun create(
        name: String,
        session: Session,
        state: String?,
        result: String?,
    ): SetupStep {
        val request = RcloneSetupWire.createRequest(name, session.type, session.parameters, state, result)
        return try {
            val step = RcloneSetupWire.parseCreateResult(name, rpc().call("config/create", request))
            if (step is SetupStep.Done) sessions.remove(name)
            step
        } catch (failure: RcloneException) {
            SetupStep.Failed(failure.message ?: "rclone could not create the remote.")
        }
    }

    /** The type and accumulated parameters needed to reproduce a follow-up `config/create`. */
    private data class Session(
        val type: String,
        val parameters: Map<String, String>,
    )
}
