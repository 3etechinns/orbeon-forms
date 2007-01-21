<!--
    Copyright (C) 2006 Orbeon, Inc.

    This program is free software; you can redistribute it and/or modify it under the terms of the
    GNU Lesser General Public License as published by the Free Software Foundation; either version
    2.1 of the License, or (at your option) any later version.

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
    without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
    See the GNU Lesser General Public License for more details.

    The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
-->

<!--
    This pipeline performs a submission using the XForms server. Do not use it directly, but can call it using the
    oxf:xforms-submission processor:

    <p:processor name="oxf:xforms-submission">
        <p:input name="submission">
            <xforms:submission method="post" action="/direct/xforms-translate/post"/>
        </p:input>
        <p:input name="request">
            <translation>
                <source>This is a table.</source>
                <language-pair>en|fr</language-pair>
            </translation>
        </p:input>
        <p:output name="response" id="response"/>
    </p:processor>
-->
<p:config xmlns:p="http://www.orbeon.com/oxf/pipeline"
    xmlns:oxf="http://www.orbeon.com/oxf/processors"
    xmlns:ev="http://www.w3.org/2001/xml-events"
    xmlns:xs="http://www.w3.org/2001/XMLSchema"
    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:xxforms="http://orbeon.org/oxf/xml/xforms"
    xmlns:xforms="http://www.w3.org/2002/xforms">

    <p:param name="submission" type="input"/>
    <p:param name="request" type="input"/>
    <p:param name="response" type="output"/>

    <!-- Encode -->
    <p:processor name="oxf:unsafe-xslt">
        <p:input name="request" href="#request"/>
        <p:input name="submission" href="#submission"/>
        <p:input name="data"><dummy/></p:input>
        <p:input name="config">
            <xxforms:event-request xsl:version="2.0" xmlns:context="java:org.orbeon.oxf.pipeline.StaticExternalContext">
                <xxforms:static-state>
                    <xsl:variable name="static-state" as="document-node()">
                        <xsl:document>
                            <static-state state-handling="client">
                                <controls>
                                    <xforms:trigger id="trigger">
                                        <xforms:send submission="default-submission" ev:event="DOMActivate"/>
                                    </xforms:trigger>
                                </controls>
                                <models>
                                    <xforms:model id="default-model">
                                        <xforms:instance id="default-instance">
                                            <xsl:copy-of select="doc('input:request')"/>
                                        </xforms:instance>
                                        <xforms:submission id="default-submission" replace="instance">
                                            <xsl:copy-of select="doc('input:submission')/xforms:submission/@*[local-name() != 'id' and local-name() != 'id']"/>
                                            <xsl:copy-of select="doc('input:submission')/xforms:submission/namespace::*"/>
                                        </xforms:submission>
                                    </xforms:model>
                                </models>
                            </static-state>
                        </xsl:document>
                    </xsl:variable>
                    <xsl:value-of select="context:encodeXML($static-state)"/>
                </xxforms:static-state>
                <xxforms:dynamic-state>
                    <xsl:variable name="dynamic-state" as="document-node()">
                        <xsl:document>
                            <dynamic-state>
                                <instances>
                                    <instance id="default-instance" model-id="default-model">
                                        <xsl:copy-of select="doc('input:request')"/>
                                    </instance>
                                </instances>
                            </dynamic-state>
                        </xsl:document>
                    </xsl:variable>
                    <xsl:value-of select="context:encodeXML($dynamic-state)"/>
                </xxforms:dynamic-state>
                <xxforms:action>
                    <xxforms:event name="DOMActivate" source-control-id="trigger"/>
                </xxforms:action>
            </xxforms:event-request>
        </p:input>
        <p:output name="data" id="encoded-request"/>
    </p:processor>

    <!-- Run XForms Server -->
    <p:processor name="oxf:xforms-server">
        <p:input name="request" href="#encoded-request" schema-href="/ops/xforms/xforms-server-request.rng"/>
        <p:output name="response" id="encoded-response" schema-href="/ops/xforms/xforms-server-response.rng"/>
    </p:processor>

    <!-- Decode -->
    <p:processor name="oxf:unsafe-xslt">
        <p:input name="data" href="#encoded-response"/>
        <p:input name="config">
            <xsl:stylesheet version="2.0" xmlns:context="java:org.orbeon.oxf.pipeline.StaticExternalContext">
                <xsl:import href="oxf:/oxf/xslt/utils/copy.xsl"/>
                <xsl:template match="/">
                    <xsl:copy-of select="context:decodeXML(normalize-space(xxforms:event-response/xxforms:dynamic-state))/dynamic-state/instances/instance[1]/*"/>
                </xsl:template>
            </xsl:stylesheet>
        </p:input>
        <p:output name="data" ref="response"/>
    </p:processor>

</p:config>
