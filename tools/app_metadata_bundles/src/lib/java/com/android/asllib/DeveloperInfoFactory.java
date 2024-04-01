/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.asllib;

import com.android.asllib.util.AslgenUtil;
import com.android.asllib.util.MalformedXmlException;

import org.w3c.dom.Element;

import java.util.List;

public class DeveloperInfoFactory implements AslMarshallableFactory<DeveloperInfo> {

    /** Creates a {@link DeveloperInfo} from the human-readable DOM element. */
    @Override
    public DeveloperInfo createFromHrElements(List<Element> elements) throws MalformedXmlException {
        Element developerInfoEle = XmlUtils.getSingleElement(elements);
        if (developerInfoEle == null) {
            AslgenUtil.logI("No DeveloperInfo found in hr format.");
            return null;
        }
        String name = XmlUtils.getStringAttr(developerInfoEle, XmlUtils.HR_ATTR_NAME);
        String email = XmlUtils.getStringAttr(developerInfoEle, XmlUtils.HR_ATTR_EMAIL);
        String address = XmlUtils.getStringAttr(developerInfoEle, XmlUtils.HR_ATTR_ADDRESS);
        String countryRegion =
                XmlUtils.getStringAttr(developerInfoEle, XmlUtils.HR_ATTR_COUNTRY_REGION);
        DeveloperInfo.DeveloperRelationship developerRelationship =
                DeveloperInfo.DeveloperRelationship.forString(
                        XmlUtils.getStringAttr(
                                developerInfoEle, XmlUtils.HR_ATTR_DEVELOPER_RELATIONSHIP));
        String website = XmlUtils.getStringAttr(developerInfoEle, XmlUtils.HR_ATTR_WEBSITE, false);
        String appDeveloperRegistryId =
                XmlUtils.getStringAttr(
                        developerInfoEle, XmlUtils.HR_ATTR_APP_DEVELOPER_REGISTRY_ID, false);

        return new DeveloperInfo(
                name,
                email,
                address,
                countryRegion,
                developerRelationship,
                website,
                appDeveloperRegistryId);
    }
}
