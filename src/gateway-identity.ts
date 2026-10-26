import type { ResolvedGatewayConfig } from './config.js'

/**
 * Attach the authenticated installation identity while retaining existing entry identity rules.
 * @param config - Validated gateway configuration containing the legacy identity.
 * @param installationId - Confirmed random identity read from this installation's state.
 * @param explicitIdentity - Whether the operator supplied the gateway identity.
 * @returns Configuration retaining certificate/operator identities or the new and legacy public identities.
 */
export function withInstallationIdentity(
  config: ResolvedGatewayConfig,
  installationId: string,
  explicitIdentity: boolean,
): ResolvedGatewayConfig {
  if (config.pairingCaFile !== undefined || explicitIdentity) return Object.freeze({ ...config, installationId })
  return Object.freeze({ ...config, installationId, instanceId: installationId, legacyInstanceId: config.instanceId })
}
