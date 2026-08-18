package io.forgetdm.mainframe;

import io.forgetdm.common.ApiException;
import io.forgetdm.config.ForgeProps;
import io.forgetdm.vault.VaultClient;
import org.springframework.stereotype.Component;

/** Resolves z/OSMF credentials without exposing secret values to controllers or job manifests. */
@Component
public class MainframeCredentialResolver {
    private final ForgeProps properties;
    private final VaultClient vault;

    public MainframeCredentialResolver(ForgeProps properties, VaultClient vault) {
        this.properties = properties;
        this.vault = vault;
    }

    public String password(MainframeConnectionEntity connection) {
        if (connection.getPasswordSecretRef() != null && !connection.getPasswordSecretRef().isBlank()) {
            if (!vault.isEnabled()) {
                throw ApiException.bad("Mainframe connection '" + connection.getName()
                        + "' uses a Vault secret reference, but Vault is not enabled");
            }
            String value = vault.readKv(connection.getPasswordSecretRef().trim());
            if (value == null || value.isBlank()) {
                throw ApiException.bad("Vault returned an empty mainframe credential for '"
                        + connection.getName() + "'");
            }
            return value;
        }
        if (connection.getPassword() != null && !connection.getPassword().isBlank()
                && properties.getMainframe().isAllowInlineCredentials()) {
            return connection.getPassword();
        }
        throw ApiException.bad("Mainframe connection '" + connection.getName()
                + "' requires passwordSecretRef; inline credentials are disabled");
    }
}
