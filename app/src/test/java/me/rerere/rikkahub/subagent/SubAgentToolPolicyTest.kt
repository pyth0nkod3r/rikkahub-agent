package me.rerere.rikkahub.subagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class SubAgentToolPolicyTest {

    @Test
    fun `default catalog permits workspace tools and explicit role tools only`() {
        val build = buildSubAgentToolPolicy(
            roleTools = listOf("custom_lookup"),
            capabilities = emptyList(),
        )
        assertTrue("policy build must succeed", build is SubAgentPolicyBuild.Ok)
        val policy = (build as SubAgentPolicyBuild.Ok).policy

        // Workspace tools always permitted
        assertTrue(policy.allows("workspace_read_file"))
        assertTrue(policy.allows("workspace_write_file"))
        assertTrue(policy.allows("workspace_shell"))

        // Explicit role tools permitted
        assertTrue(policy.allows("custom_lookup"))

        // Assistant surface tools denied by default
        assertFalse("memory_tool must be denied by default", policy.allows("memory_tool"))
        assertFalse("shizuku_exec must be denied by default", policy.allows("shizuku_exec"))
        assertFalse("termux_run_command must be denied by default", policy.allows("termux_run_command"))
        assertFalse("ssh_exec must be denied by default", policy.allows("ssh_exec"))
        assertFalse("keystore_sign must be denied by default", policy.allows("keystore_sign"))
        assertFalse("schedule_job must be denied by default", policy.allows("schedule_job"))
        assertFalse("workflow_run must be denied by default", policy.allows("workflow_run"))
        assertFalse("subagent_dispatch must be denied by default", policy.allows("subagent_dispatch"))
        assertFalse("send_sms must be denied by default", policy.allows("send_sms"))
    }

    @Test
    fun `opt-in capabilities grant their respective tools`() {
        val build = buildSubAgentToolPolicy(
            roleTools = emptyList(),
            capabilities = listOf("memory", "ssh", "keystore"),
        )
        assertTrue(build is SubAgentPolicyBuild.Ok)
        val policy = (build as SubAgentPolicyBuild.Ok).policy

        // Granted
        assertTrue(policy.allows("memory_tool"))
        assertTrue(policy.allows("ssh_exec"))
        assertTrue(policy.allows("ssh_upload"))
        assertTrue(policy.allows("keystore_generate_key"))
        assertTrue(policy.allows("keystore_sign"))

        // Not granted
        assertFalse(policy.allows("schedule_job"))
        assertFalse(policy.allows("workflow_run"))
        assertFalse(policy.allows("shizuku_exec"))
        assertFalse(policy.allows("subagent_dispatch"))
    }

    @Test
    fun `unknown capabilities fail policy build`() {
        val build = buildSubAgentToolPolicy(
            roleTools = emptyList(),
            capabilities = listOf("memory", "invalid_cap_name"),
        )
        assertTrue("unknown capability must fail the build", build is SubAgentPolicyBuild.UnknownCapability)
        val unknown = (build as SubAgentPolicyBuild.UnknownCapability).names
        assertEquals(listOf("invalid_cap_name"), unknown)
    }

    @Test
    fun `SubAgentToolPolicies tracks subagent conversations and cleans up`() {
        val convId = Uuid.random()
        val build = buildSubAgentToolPolicy(
            roleTools = listOf("role_tool_x"),
            capabilities = emptyList(),
        )
        val policy = (build as SubAgentPolicyBuild.Ok).policy

        assertFalse(SubAgentToolPolicies.isSubAgent(convId))
        assertTrue("unregistered conversation permits everything by default", SubAgentToolPolicies.permits(convId, "shizuku_exec"))

        SubAgentToolPolicies.register(convId, policy)
        assertTrue(SubAgentToolPolicies.isSubAgent(convId))
        assertTrue(SubAgentToolPolicies.permits(convId, "workspace_shell"))
        assertTrue(SubAgentToolPolicies.permits(convId, "role_tool_x"))
        assertFalse("shizuku_exec must be blocked under subagent policy", SubAgentToolPolicies.permits(convId, "shizuku_exec"))

        SubAgentToolPolicies.unregister(convId)
        assertFalse(SubAgentToolPolicies.isSubAgent(convId))
    }
}
