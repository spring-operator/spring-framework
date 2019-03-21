/*
 * Copyright 2002-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.orm.jpa.hibernate;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.junit.Test;

import org.springframework.orm.jpa.AbstractContainerEntityManagerFactoryIntegrationTests;
import org.springframework.orm.jpa.EntityManagerFactoryInfo;
import org.springframework.orm.jpa.EntityManagerProxy;

import static org.junit.Assert.*;

/**
 * Hibernate-specific JPA tests.
 *
 * @author Juergen Hoeller
 * @author Rod Johnson
 */
public class HibernateEntityManagerFactoryIntegrationTests extends AbstractContainerEntityManagerFactoryIntegrationTests {

	@Override
	protected String[] getConfigLocations() {
		return HIBERNATE_CONFIG_LOCATIONS;
	}


	@Test
	public void testCanCastNativeEntityManagerFactoryToHibernateEntityManagerFactoryImpl() {
		EntityManagerFactoryInfo emfi = (EntityManagerFactoryInfo) entityManagerFactory;
		assertTrue(emfi.getNativeEntityManagerFactory() instanceof SessionFactory);  // as of Hibernate 5.2
	}

	@Test
	public void testCanCastSharedEntityManagerProxyToHibernateEntityManager() {
		assertTrue(((EntityManagerProxy) sharedEntityManager).getTargetEntityManager() instanceof Session);  // as of Hibernate 5.2
	}

}
