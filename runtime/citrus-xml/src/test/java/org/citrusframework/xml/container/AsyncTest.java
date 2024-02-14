/*
 * Copyright 2022 the original author or authors.
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.citrusframework.xml.container;

import org.citrusframework.TestCase;
import org.citrusframework.TestCaseMetaInfo;
import org.citrusframework.actions.EchoAction;
import org.citrusframework.container.Async;
import org.citrusframework.xml.XmlTestLoader;
import org.citrusframework.xml.actions.AbstractXmlActionTest;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * @author Christoph Deppisch
 */
public class AsyncTest extends AbstractXmlActionTest {

    @Test
    public void shouldLoadAsync() {
        XmlTestLoader testLoader = createTestLoader("classpath:org/citrusframework/xml/container/async-test.xml");

        testLoader.load();
        TestCase result = testLoader.getTestCase();
        Assert.assertEquals(result.getName(), "AsyncTest");
        Assert.assertEquals(result.getMetaInfo().getAuthor(), "Christoph");
        Assert.assertEquals(result.getMetaInfo().getStatus(), TestCaseMetaInfo.Status.FINAL);
        Assert.assertEquals(result.getActionCount(), 2L);

        Assert.assertEquals(result.getTestAction(0).getClass(), org.citrusframework.container.Async.class);

        int actionIndex = 0;

        org.citrusframework.container.Async action = (Async) result.getTestAction(actionIndex++);
        Assert.assertEquals(action.getActionCount(), 2L);
        Assert.assertEquals(action.getSuccessActions().size(), 0L);
        Assert.assertEquals(action.getErrorActions().size(), 0L);

        action = (Async) result.getTestAction(actionIndex);
        Assert.assertEquals(action.getActionCount(), 1L);
        Assert.assertEquals(action.getSuccessActions().size(), 1L);
        Assert.assertEquals(((EchoAction)action.getSuccessActions().get(0)).getMessage(), "Success!");
        Assert.assertEquals(action.getErrorActions().size(), 1L);
        Assert.assertEquals(((EchoAction)action.getErrorActions().get(0)).getMessage(), "Failed!");

    }

}
