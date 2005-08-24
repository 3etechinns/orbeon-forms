<!--
    Copyright (C) 2005 Orbeon, Inc.

    This program is free software; you can redistribute it and/or modify it under the terms of the
    GNU Lesser General Public License as published by the Free Software Foundation; either version
    2.1 of the License, or (at your option) any later version.

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
    without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
    See the GNU Lesser General Public License for more details.

    The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
-->
<p:config xmlns:p="http://www.orbeon.com/oxf/pipeline"
          xmlns:oxf="http://www.orbeon.com/oxf/processors"
          xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
          xmlns:xs="http://www.w3.org/2001/XMLSchema"
          xmlns:xdb="http://orbeon.org/oxf/xml/xmldb"
          xmlns:xu="http://www.xmldb.org/xupdate"
          xmlns:xmldb="http://exist-db.org/xquery/xmldb">

    <p:param type="input" name="query"/>

    <!-- Use special eXist functions -->
    <p:processor name="oxf:xslt">
        <p:input name="data" href="#query"/>
        <p:input name="config">
            <xdb:query collection="/db/system" create-collection="false" xsl:version="2.0">
                xquery version "1.0";
                <result>
                    {
                    if (xmldb:get-user-groups('<xsl:value-of select="/query/username"/>') = 'ops-users')
                        then xmldb:delete-user('<xsl:value-of select="/query/username"/>')
                        else ()
                    }
                </result>
            </xdb:query>
        </p:input>
        <p:output name="data" id="xmldb-query"/>
    </p:processor>

    <p:processor name="oxf:xmldb-query">
        <p:input name="datasource" href="../datasource.xml"/>
        <p:input name="query" href="#xmldb-query"/>
    </p:processor>

</p:config>
