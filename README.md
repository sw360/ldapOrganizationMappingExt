# ldapAdapterEXT

This extends the liferay class `PortalLDAPImporterImpl` by the ability of
syncing the **department** in LDAP to **organization** in liferay.

In the corresponding LDAP configuration for liferay one has to set the custom
user mapping
```
organization=department
```
where `department` is the corresponding field in LDAP.
This allows the code in `CustomPortalLDAPImporterImpl` to access the LDAP field
`department`.

If that field is set, the application tries to find an organization with this
name.
- If such an organization exists, then will the user be added to this
  organization,
- otherwise it will create that organization and add the corresponding user to
  the organization.
  
### Compilation
One can create the war-files with the command `mvn package`.

### Deployment
One should deploy this plugin before all other things. It is even recommended to
start with a clean database.

Steps to do after copying the two war files to the liferay deploy folder:
1. ensure that liferay is running and that the auto-deploy-scanner has extracted
  and deployed the war files
2. restart liferay, i.e. the corresponding server, two to three times and wait
  for liferay to finish deployment, i.e. wait for the marketplace-portlet to
  finish
  
The deployment was successful if the log contains the message
`"Instantiate CustomPortalLDAPImporterImpl from ldapAdapterEXT plugin"` and one
should restart once more, if the log states that further rebooting is needed for
deployment completions of this plugin.
  
### Problems
- The user logging in via LDAP **should not be owner** of the on-demand created
  organization. A solution would be, to add an dummy user without privileges whose
  only purpose is to be owner of that organization.
  
- This plugin needs a more descriptive name.
