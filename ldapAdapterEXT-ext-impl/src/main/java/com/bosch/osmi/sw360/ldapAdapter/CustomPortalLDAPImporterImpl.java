package com.bosch.osmi.sw360.ldapAdapter;

import com.liferay.portal.NoSuchOrganizationException;
import com.liferay.portal.kernel.bean.PortalBeanLocatorUtil;
import com.liferay.portal.kernel.exception.PortalException;
import com.liferay.portal.kernel.exception.SystemException;
import com.liferay.portal.model.*;
import com.liferay.portal.security.ldap.LDAPSettingsUtil;
import com.liferay.portal.security.ldap.PortalLDAPImporterImpl;
import com.liferay.portal.security.membershippolicy.OrganizationMembershipPolicyUtil;
import com.liferay.portal.service.OrganizationLocalService;
import com.liferay.portal.service.OrganizationLocalServiceUtil;
import com.liferay.portal.service.UserLocalServiceUtil;
import org.apache.log4j.Logger;

import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.ldap.LdapContext;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.apache.log4j.LogManager.getLogger;

public class CustomPortalLDAPImporterImpl extends PortalLDAPImporterImpl {
	private static final String MAPPING_KEYS_PREFIX = "mapping.";
	private static final String MAPPING_VALUES_SUFFIX = ".target";
	private boolean matchPrefix = false;
	private Map<String, String> organizationMappings;
	private boolean defaultToNull = false;
	private Logger log = Logger.getLogger(CustomPortalLDAPImporterImpl.class);
	private static String ORGANIZATION_KEY_KEY = "organization";
	private static final String SYSTEM_CONFIGURATION_PATH_KEY = "system.config.path";
	private static final String MATCH_PREFIX_KEY = "match.prefix";
	private static final String DEFAULT_TO_NULL_KEY = "default.to.none";
	private static final String PROPERTIES_FILE_PATH = "/ldapimporter.properties";

	private static String SYSTEM_CONFIGURATION_PATH = "";

	static {
		Properties localProperties = loadProperties(CustomPortalLDAPImporterImpl.class, PROPERTIES_FILE_PATH, false);
		String sysConfigPath = localProperties.getProperty(SYSTEM_CONFIGURATION_PATH_KEY);
		if (sysConfigPath != null && sysConfigPath.length() != 0){
			SYSTEM_CONFIGURATION_PATH = sysConfigPath;
		}
	}

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

		log.debug("CustomPortalLDAPImporterImpl read LDAP organization name: " + organizationName);
		String mappedOrganizationName = mapOrganizationName(organizationName);
		if (mappedOrganizationName == null){
			return null;
		}

		log.debug("CustomPortalLDAPImporterImpl adds or gets the organization " + mappedOrganizationName);
		return addOrGetOrganization(mappedOrganizationName, user, companyId);
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
							"Automatically created during LDAP import",
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

	private String mapOrganizationName(String name) {
		loadLDAPImporterProperties();
		String defaultTarget = defaultToNull ? null : name;

		if (matchPrefix) {
			return organizationMappings.keySet().stream()
					.filter(name::startsWith)
					.sorted(Comparator.comparing(String::length).reversed())
					.findFirst()
					.map(organizationMappings::get)
					.orElse(defaultTarget);
		} else { // match complete name
			return organizationMappings.getOrDefault(name, defaultTarget);
		}
	}

	private void loadLDAPImporterProperties() {
		Properties ldapConfigProperties = loadProperties(CustomPortalLDAPImporterImpl.class, PROPERTIES_FILE_PATH);
		matchPrefix = Boolean.parseBoolean(ldapConfigProperties.getProperty(MATCH_PREFIX_KEY, "false"));
		defaultToNull = Boolean.parseBoolean(ldapConfigProperties.getProperty(DEFAULT_TO_NULL_KEY, "false"));

		List<Object> mappingSourceKeys = ldapConfigProperties
				.keySet()
				.stream()
				.filter(p -> ((String) p).startsWith(MAPPING_KEYS_PREFIX) && !((String) p).endsWith(MAPPING_VALUES_SUFFIX))
				.collect(Collectors.toList());

		organizationMappings = new HashMap<>();
		for (Object sourceKey : mappingSourceKeys) {
			String sourceOrg = ldapConfigProperties.getProperty((String)sourceKey);
			String targetOrg = ldapConfigProperties.getProperty(sourceKey+MAPPING_VALUES_SUFFIX);
			if (sourceOrg != null && targetOrg != null && sourceOrg.length() > 0 && targetOrg.length() > 0) {
				organizationMappings.put(sourceOrg, targetOrg);
			}
		}
	}

	/**
	 * Copied from https://github.com/sw360/sw360portal/blob/master/libraries/lib-datahandler/src/main/java/com/siemens/sw360/datahandler/common/CommonUtils.java
	 */
	private static Properties loadProperties(Class<?> clazz, String propertiesFilePath) {
		return loadProperties(clazz, propertiesFilePath, true);
	}

	/**
	 * Copied from https://github.com/sw360/sw360portal/blob/master/libraries/lib-datahandler/src/main/java/com/siemens/sw360/datahandler/common/CommonUtils.java
	 */
	private static Properties loadProperties(Class<?> clazz, String propertiesFilePath, boolean useSystemConfig) {
		Properties props = new Properties();

		try (InputStream resourceAsStream = clazz.getResourceAsStream(propertiesFilePath)) {
			if (resourceAsStream == null)
				throw new IOException("cannot open " + propertiesFilePath);

			props.load(resourceAsStream);
		} catch (IOException e) {
			getLogger(clazz).error("Error opening resources " + propertiesFilePath + ".", e);
		}

		if(useSystemConfig){
			File systemPropertiesFile = new File(SYSTEM_CONFIGURATION_PATH, propertiesFilePath);
			if(systemPropertiesFile.exists()){
				try (InputStream resourceAsStream = new FileInputStream(systemPropertiesFile.getPath())) {
					if (resourceAsStream == null)
						throw new IOException("cannot open " + systemPropertiesFile.getPath());

					props.load(resourceAsStream);
				} catch (IOException e) {
					getLogger(clazz).error("Error opening resources " + systemPropertiesFile.getPath() + ".", e);
				}
			}
		}
		return props;
	}
}

