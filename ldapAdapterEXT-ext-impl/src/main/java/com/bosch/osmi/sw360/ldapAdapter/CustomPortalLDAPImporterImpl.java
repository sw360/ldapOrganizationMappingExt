package com.bosch.osmi.sw360.ldapAdapter;

import com.liferay.portal.NoSuchOrganizationException;
import com.liferay.portal.kernel.bean.PortalBeanLocatorUtil;
import com.liferay.portal.kernel.exception.PortalException;
import com.liferay.portal.kernel.exception.SystemException;
import com.liferay.portal.model.*;
import com.liferay.portal.security.ldap.LDAPSettingsUtil;
import com.liferay.portal.security.ldap.PortalLDAPImporterImpl;
import com.liferay.portal.security.membershippolicy.OrganizationMembershipPolicyUtil;
import com.liferay.portal.service.*;
import org.apache.log4j.Logger;

import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.ldap.LdapContext;
import java.util.Collections;
import java.util.Properties;
import java.util.stream.LongStream;

public class CustomPortalLDAPImporterImpl extends PortalLDAPImporterImpl {
	private Logger log = Logger.getLogger(CustomPortalLDAPImporterImpl.class);
	private static String ORGANIZATION_KEY_KEY = "organization";

	public CustomPortalLDAPImporterImpl(){
		log.info("Instantiate CustomPortalLDAPImporterImpl from ldapAdapterEXT plugin");
	}

	@Override
	public User importLDAPUser(long ldapServerId, long companyId, LdapContext ldapContext, Attributes attributes, String password) throws Exception {
		User user = super.importLDAPUser(ldapServerId, companyId, ldapContext, attributes, password);
		log.debug("CustomPortalLDAPImporterImpl sees user with email: " + user.getEmailAddress());

		Properties userExpandoMappings = LDAPSettingsUtil.getUserExpandoMappings(ldapServerId, companyId);

		final Organization organization = importOrganization(userExpandoMappings, attributes, user, companyId);
		if (organization != null && userHasNotOrganization(user, organization.getOrganizationId())) {
		    removeUserFromOtherOrganizations(user);
			log.debug("CustomPortalLDAPImporterImpl adds user " + user.getEmailAddress() + " to the organization " + organization.getName());
			UserLocalServiceUtil.addOrganizationUsers(organization.getOrganizationId(), Collections.singletonList(user));
		}

		return user;
	}

	private boolean userHasNotOrganization(User user, long organizationId) throws SystemException, PortalException {
		return ! LongStream.of(user.getOrganizationIds()).anyMatch(x -> x == organizationId);
	}

	private void removeUserFromOtherOrganizations(User user) throws SystemException, PortalException {
		long[] usersOrganizations = user.getOrganizationIds();
		for (long usersOrganization : usersOrganizations ) {
			log.info("remove user " + user.getEmailAddress() + " from organization with id: " + usersOrganization);
			UserLocalServiceUtil.deleteOrganizationUser(usersOrganization, user);
		}
	}

	private Organization importOrganization(Properties userExpandoMappings, Attributes attributes, User user, long companyId) {
		String organizationKey = userExpandoMappings.getProperty(ORGANIZATION_KEY_KEY);
		if (organizationKey == null) {
			return null;
		}

		Attribute attribute = attributes.get(organizationKey);
		if (attribute == null) {
			return null;
		}

		Object object = null;
		try {
			object = attribute.get();
		} catch (NamingException e) {
			log.error(e);
		}
		if (object == null) {
			return null;
		}

		String organizationName = object.toString();
		if (organizationName.length() == 0) {
			return null;
		}

		log.debug("CustomPortalLDAPImporterImpl adds or gets the organization " + organizationName);
		return addOrGetOrganization(organizationName, user, companyId);
	}

	private Organization addOrGetOrganization(String organizationName, User user, long companyId) {
		Organization organization = null;
		try {
			try {
				organization = OrganizationLocalServiceUtil
						.getOrganization(companyId, organizationName);
			} catch (NoSuchOrganizationException e) {
				organization = addOrganization(organizationName, user);
			} catch (PortalException e) {
				log.error("The only thrown PortalException should be a NoSuchOrganizationException", e);
			}
		} catch (SystemException e) {
		    log.error(e);
		}

		return organization;
	}

	private Organization addOrganization(String organizationName, User user) throws SystemException {
		log.info("CustomPortalLDAPImporterImpl adds the organization " + organizationName);
		Organization organization = null;
		try {
			OrganizationLocalService organizationLocalService = (OrganizationLocalService) PortalBeanLocatorUtil.locate(OrganizationLocalService.class.getName());
			organization = organizationLocalService
					.addOrganization(
							user.getUserId(),
							OrganizationConstants.DEFAULT_PARENT_ORGANIZATION_ID,
							organizationName,
							OrganizationConstants.TYPE_REGULAR_ORGANIZATION,
							RegionConstants.DEFAULT_REGION_ID,
							CountryConstants.DEFAULT_COUNTRY_ID,
							ListTypeConstants.ORGANIZATION_STATUS_DEFAULT,
							"Automatically created while LDAP import",
							false,
                            null
					);

			OrganizationMembershipPolicyUtil.verifyPolicy(organization);

			log.info("added organization with name: " + organization.getName() + " and id:" + organization.getOrganizationId());
		} catch (PortalException e) {
		    log.error("A creator or parent organization with the primary key could not be found or the organization's information was invalid", e);
		}
        return organization;
	}
}

