/*******************************************************************************
 * Copyright (c) 2012-2017 Codenvy, S.A.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Codenvy, S.A. - initial API and implementation
 *******************************************************************************/
package org.eclipse.che.plugin.docker.machine.node;

import org.eclipse.che.api.core.ServerException;
import org.eclipse.che.api.machine.server.spi.InstanceNode;

/**
 * Provides access to operation machines need but not supported by the Docker
 *
 * @author Alexander Garagatyi
 */
public interface DockerNode extends InstanceNode {
    /**
     * Bind the whole workspace on the Node.
     *
     * @throws ServerException
     *         if error occurs on binding
     */
    void bindWorkspace() throws ServerException;

    /**
     * Unbind the workspace on Node.
     *
     * @throws ServerException
     *         if error occurs on binding
     */
    void unbindWorkspace() throws ServerException;

    @Override
    String getHost();

    /**
     * Returns IP of the node where container is deployed.
     */
    String getIp();
}
