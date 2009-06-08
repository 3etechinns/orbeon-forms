/**
*  Copyright (C) 2009 Orbeon, Inc.
*
*  This program is free software; you can redistribute it and/or modify it under the terms of the
*  GNU Lesser General Public License as published by the Free Software Foundation; either version
*  2.1 of the License, or (at your option) any later version.
*
*  This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
*  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
*  See the GNU Lesser General Public License for more details.
*
*  The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
*/
/*
*  JavaScript implementation for the datatable component (new generation)
*
*/

/**
* Implementation of datatable constructor. Creates column resizers.
*
* @method ORBEON.widgets.datatable
* @param element {DOM Element} The DOM element that contains the table.
* @param index {integer} Index (position) of the table in the document.
*        Currently not used, but might be useful to generate IDs.
*/
ORBEON.widgets.datatable = function (element, index) {

    YAHOO.log("Creating datatable index " + index, "info")
    // Store useful stuff as properties
    this.table = element;
    this.header = this.table;
    this.headerColumns = this.header.getElementsByTagName('thead')[0].getElementsByTagName('tr')[0].getElementsByTagName('th');
    this.bodyRows = this.table.getElementsByTagName('tbody')[0].getElementsByTagName('tr');
    this.bodyColumns = this.bodyRows[2].getElementsByTagName('td');
    var plainId = this.table.getAttribute('id');
    this.id = plainId.substring(0, plainId.length - '-table'.length);
    var width = ORBEON.widgets.datatable.utils.getStyle(this.table, 'width', 'auto');
    var region = YAHOO.util.Dom.getRegion(this.table);
    var pxWidth = region.right - region.left;
    if (width.indexOf('%') != - 1) {
        // Convert % into px...
        width = pxWidth + 'px';
    }
    this.height = ORBEON.widgets.datatable.utils.getStyle(this.table, 'height', 'auto');
    this.scrollV = YAHOO.util.Dom.hasClass(this.table, 'fr-scrollV');
    this.scrollH = YAHOO.util.Dom.hasClass(this.table, 'fr-scrollH');
    this.scroll = this.scrollV || this.scrollH;
    this.headBodySplit = this.scroll;
    this.hasFixedWidthContainer = width != 'auto';
    this.hasFixedWidthTable = this.hasFixedWidthContainer && ! this.scrollH;

    // Create a global container
    this.container = document.createElement('div');
    YAHOO.util.Dom.addClass(this.container, 'yui-dt');
    YAHOO.util.Dom.addClass(this.container, 'yui-dt-scrollable');

    // Create a container for the header (or the whole table if there is no scrolling)
    this.headerContainer = document.createElement('div');
    YAHOO.util.Dom.addClass(this.headerContainer, 'yui-dt-hd');

    // Assemble all that stuff
    this.table.parentNode.replaceChild(this.container, this.table);
    this.container.appendChild(this.headerContainer);
    this.headerContainer.appendChild(this.header);

    // See how big the table would be without its size restriction
    if (this.scrollH) {
        YAHOO.util.Dom.setStyle(this.table, 'width', 'auto');
    }
    if (this.scrollV) {
        YAHOO.util.Dom.setStyle(this.table, 'height', 'auto');
    }

    region = YAHOO.util.Dom.getRegion(this.table);
    this.tableWidth = (region.right - region.left);
    this.tableHeight = (region.bottom - region.top);
    if (this.scrollH) {
        if (pxWidth > this.tableWidth) {
            // Can be the case if table width was expressed as %
            this.tableWidth = pxWidth;
        }
        for (var i = this.tableWidth; i < 2000; i += 50) {
            YAHOO.util.Dom.setStyle(this.table, 'width', i + 'px');
            region = YAHOO.util.Dom.getRegion(this.table);
            var tableWidth = (region.right - region.left);
            var tableHeight = (region.bottom - region.top);
            if (tableHeight < this.tableHeight) {
                this.tableWidth = tableWidth;
                this.tableHeight = tableHeight;
            }
        }
        this.tableWidth = this.tableWidth + 19;
    } else if (this.scrollV) {
        if (this.hasFixedWidthTable) {
            width = this.tableWidth + 'px';
            this.tableWidth = this.tableWidth - 19;
        } else {
            width = (this.tableWidth + 19) + 'px';
        }
    } else {
        width = this.tableWidth + 'px';
    }
    YAHOO.util.Dom.setStyle(this.table, 'width', this.tableWidth + 'px');

    if (this.scrollH && ! this.scrollV && this.height == 'auto' && YAHOO.env.ua.ie > 0) {
        this.height = (this.tableHeight + 22) + 'px';
    }

    this.headerRegion = YAHOO.util.Dom.getRegion(YAHOO.util.Selector.query('thead', this.table, true));
    this.bodyRegion = YAHOO.util.Dom.getRegion(YAHOO.util.Selector.query('tbody', this.table, true));

    // Resize the container
    YAHOO.util.Dom.setStyle(this.container, 'width', width);
    if (this.height != 'auto') {
        YAHOO.util.Dom.setStyle(this.container, 'height', this.height);
    }

    // Resize the header container
    YAHOO.util.Dom.setStyle(this.headerContainer, 'width', width);
    if (this.height != 'auto' && this.headBodySplit) {
        YAHOO.util.Dom.setStyle(this.headerContainer, 'height', (this.headerRegion.bottom - this.headerRegion.top) + 'px');
    } else if (! this.headBodySplit && this.height == 'auto') {
        YAHOO.util.Dom.setStyle(this.headerContainer, 'border', '1px solid #7F7F7F')
    }

    if (this.headBodySplit) {

        // Create a container for the body
        this.bodyContainer = document.createElement('div');
        YAHOO.util.Dom.setStyle(this.bodyContainer, 'width', width);
        YAHOO.util.Dom.addClass(this.bodyContainer, 'yui-dt-bd');

        // Duplicate the table to populate the body

        this.table = this.header.cloneNode(true);
        ORBEON.widgets.datatable.removeIdAttributes(this.table);

        // Move the tbody elements to keep event handler bindings in the visible tbody
        var tBody = YAHOO.util.Selector.query('tbody', this.header, true);
        this.header.removeChild(tBody);
        this.table.replaceChild(tBody, YAHOO.util.Selector.query('tbody', this.table, true));



        // Do more resizing
        this.containerRegion = YAHOO.util.Dom.getRegion(this.container);
        if (this.height != 'auto') {
            YAHOO.util.Dom.setStyle(this.bodyContainer, 'height', ((this.containerRegion.bottom - this.containerRegion.top) - (this.headerRegion.bottom - this.headerRegion.top) - 5) + 'px');
        }

        // And more assembly
        this.container.appendChild(this.bodyContainer);
        this.bodyContainer.appendChild(this.table);


        for (var i = 0; i < this.bodyColumns.length; i++) {
            var r = YAHOO.util.Dom.getRegion(this.bodyColumns[i]);
            YAHOO.util.Dom.setStyle(this.headerColumns[i], 'width', (r.right - r.left - 1) + 'px');
        }
    }

    region = YAHOO.util.Dom.getRegion(this.container);
    this.width = region.right - region.left;

    if (this.scrollH) {
        YAHOO.util.Event.addListener(this.bodyContainer, 'scroll', ORBEON.widgets.datatable.scrollHandler, this, true);
    }

    this.colResizers =[];
    this.colSorters =[];
    for (var j = 0; j < this.headerColumns.length; j++) {
        var childDiv = YAHOO.util.Selector.query('div', this.headerColumns[j], true);
        var colResizer = null;
        if (YAHOO.util.Dom.hasClass(this.headerColumns[j], 'yui-dt-resizeable')) {
            colResizer = new ORBEON.widgets.datatable.colResizer(j, this.headerColumns[j], this)
            this.colResizers[ this.colResizers.length] = colResizer;
        }
        var region = YAHOO.util.Dom.getRegion(this.headerColumns[j]);
        var width = (region.right - region.left - 20) + 'px';
        var rule;
        // See _setColumnWidth in YUI datatable.js...
        if (YAHOO.env.ua.ie == 0) {
            // This is a hack! We need to remove the prefix to match classes added in XSLT!
            var className = '.dt-' + this.id.substring(this.id.lastIndexOf('$') + 1) + '-col-' + (j + 1);
            if (! this.styleElt) {
                this.styleElt = document.createElement('style');
                this.styleElt.type = 'text/css';
                document.getElementsByTagName('head').item(0).appendChild(this.styleElt);
            }
            if (this.styleElt) {
                if (this.styleElt.styleSheet && this.styleElt.styleSheet.addRule) {
                    this.styleElt.styleSheet.addRule(classname, 'width:' + width);
                    rule = this.styleElt.styleSheet.rules[ this.styleElt.styleSheet.rules.length - 1];
                } else if (this.styleElt.sheet && this.styleElt.sheet.insertRule) {
                    this.styleElt.sheet.insertRule(className + ' {width:' + width + ';}', this.styleElt.sheet.cssRules.length);
                    rule = this.styleElt.sheet.cssRules[ this.styleElt.sheet.cssRules.length - 1];
                }
            }
            if (rule && YAHOO.util.Dom.hasClass(this.headerColumns[j], 'yui-dt-resizeable')) {
                colResizer.setRule(rule);
            }
        }
        if (! rule) {
            var style = childDiv.style;
            style.width = width;
            var styles =[style];
            for (var k = 0; k < this.bodyRows.length; k++) {
                row = this.bodyRows[k];
                if (row.cells.length > j && ! YAHOO.util.Dom.hasClass(row, 'xforms-repeat-template')) {
                    style = YAHOO.util.Selector.query('div', row.cells[j], true).style;
                    style.width = width;
                    styles[styles.length] = style;
                }
            }
            if (YAHOO.util.Dom.hasClass(this.headerColumns[j], 'yui-dt-resizeable')) {
                colResizer.setStyleArray(styles);
            }
        }

        if (YAHOO.util.Dom.hasClass(this.headerColumns[j], 'yui-dt-sortable')) {
            this.colSorters[ this.colSorters.length] = new ORBEON.widgets.datatable.colSorter(this.headerColumns[j]);
        }
    }

    // Now that the table has been properly sized, reconsider its
    // "resizeability"

    if (this.hasFixedWidthContainer && this.hasFixedWidthTable) {
        // These are fixed width tables without horizontal scroll bars
        // and as we don't know how to resize their columns properly,
        // we'd better consider that as variable width
        this.hasFixedWidthContainer = false;
        this.hasFixedWidthTable = false;
    }

}


ORBEON.widgets.datatable.prototype.adjustWidth = function (deltaX, index) {
    if (! this.hasFixedWidthContainer) {
        this.width += deltaX;
        YAHOO.util.Dom.setStyle(this.container, 'width', this.width + 'px');
        YAHOO.util.Dom.setStyle(this.headerContainer, 'width', this.width + 'px');
        if (this.headBodySplit) {
            YAHOO.util.Dom.setStyle(this.bodyContainer, 'width', this.width + 'px');
        }
    }
    if (! this.hasFixedWidthTable) {
        this.tableWidth += deltaX;
        YAHOO.util.Dom.setStyle(this.header, 'width', this.tableWidth + 'px');
        if (this.headBodySplit) {
            YAHOO.util.Dom.setStyle(this.table, 'width', this.tableWidth + 'px');
        }
    }
}

ORBEON.widgets.datatable.scrollHandler = function (e) {
    //alert('scrolling');
    this.headerContainer.scrollLeft = this.bodyContainer.scrollLeft;
}

ORBEON.widgets.datatable.utils =[];

ORBEON.widgets.datatable.utils.getStyle = function (elt, property, defolt) {
    if (elt.style[property] == '') {
        return defolt;
    }
    return elt.style[property];
}

ORBEON.widgets.datatable.utils.freezeWidth = function (elt) {
    region = YAHOO.util.Dom.getRegion(elt);
    YAHOO.util.Dom.setStyle(elt, 'width', (region.right - region.left) + 'px');
}

ORBEON.widgets.datatable.colSorter = function (th) {
    var liner = YAHOO.util.Selector.query('div.yui-dt-liner', th, true);
    YAHOO.util.Event.addListener(liner, "click", function (ev) {
        var a = YAHOO.util.Selector.query('a.xforms-trigger:not(.xforms-disabled)', liner, true);
        if (YAHOO.util.Event.getTarget(ev) != a) {
            ORBEON.xforms.Document.dispatchEvent(a.id, "DOMActivate");
        }
    });
}

/**
* Implementation of datatable.colResizer constructor. Creates the YAHOO.util.DD object.
*
* @method ORBEON.widgets.datatable.colResizer
* @param col {DOM Element} The th DOM element.
*/
ORBEON.widgets.datatable.colResizer = function (index, th, datatable) {
    this.index = index;
    this.th = th;
    this.datatable = datatable;
    this.rule = null;
    this.styles = null;

    this.resizerliner = document.createElement('div');
    YAHOO.util.Dom.addClass(this.resizerliner, 'yui-dt-resizerliner');

    this.liner = YAHOO.util.Selector.query('div', this.th, true);

    this.th.replaceChild(this.resizerliner, this.liner);

    this.resizerliner.appendChild(this.liner);

    this.resizer = document.createElement('div');
    YAHOO.util.Dom.addClass(this.resizer, 'yui-dt-resizer');
    this.resizer.style.left = 'auto';
    this.resizer.style.right = '0pt';
    this.resizer.style.top = 'auto';
    this.resizer.style.bottom = '0pt';
    this.resizer.style.height = '25px';
    this.resizerliner.appendChild(this.resizer);


    this.init(this.resizer, this.resizer, {
        dragOnly: true, dragElId: this.resizer.id
    });
    this.setYConstraint(0, 0);
    this.initFrame();

    var colRegion = YAHOO.util.Dom.getRegion(this.th);
    var X = YAHOO.util.Dom.getX(this.resizer);
    this.delta = colRegion.right - X;
}


YAHOO.extend(ORBEON.widgets.datatable.colResizer, YAHOO.util.DDProxy, {
    /////////////////////////////////////////////////////////////////////////////
    //
    // Public methods
    //
    /////////////////////////////////////////////////////////////////////////////

    setStyleArray: function (styles) {
        this.styles = styles;
    },

    setRule: function (rule) {
        this.rule = rule;
    },

    /////////////////////////////////////////////////////////////////////////////
    //
    // Public DOM event handlers
    //
    /////////////////////////////////////////////////////////////////////////////


    /**
    * Handles mousedown events on the Column resizer.
    *
    * @method onMouseDown
    * @param e {string} The mousedown event
    */
    onMouseDown: function (ev) {
        this.resetConstraints();
        var region = YAHOO.util.Dom.getRegion(this.liner);
        this.width = region.right - region.left;
        //this.resizerX = YAHOO.util.Dom.getX(this.resizer);
        this.resizerX = YAHOO.util.Event.getXY(ev)[0];
    },


    /**
    * Handles drag events on the Column resizer.
    *
    * @method onDrag
    * @param e {string} The drag event
    */
    onDrag: function (ev) {
        var newX = YAHOO.util.Event.getXY(ev)[0];
        this.datatable.table.style.display = 'none';
        var deltaX = newX - this.resizerX;
        this.datatable.adjustWidth(deltaX, this.index);
        this.resizerX = newX;
        var width = this.width + deltaX;
        var widthStyle = (width - 20) + 'px'; //TODO : determine 20 from padding
        // If different and non null, try to set it
        if (width > 0 && width != this.width) {
            if (this.rule) {
                this.rule.style.width = widthStyle;
            } else {
                for (var i = 0; i < this.styles.length; i++) {
                    this.styles[i].width = widthStyle;
                }
            }
            this.width = width;
        }
        this.datatable.table.style.display = '';
    }
});

ORBEON.widgets.datatable.removeIdAttributes = function (element, skipSelf) {
    if (! skipSelf) {
        element.removeAttribute('id');
    }
    for (var i = 0; i < element.childNodes.length; i++) {
        var node = element.childNodes[i];
        if (node.nodeType == 1) {
            ORBEON.widgets.datatable.removeIdAttributes(node);
        }
    }
}


ORBEON.widgets.datatable.init = function (klass) {
    // Initializes a datatable (called by xforms-enabled events)
    if (ORBEON.widgets.datatable.datatables[klass] == undefined) {
        var tables = YAHOO.util.Dom.getElementsByClassName(klass, 'table');
        ORBEON.widgets.datatable.datatables[klass] = new ORBEON.widgets.datatable(tables[0], klass);
    }
}

// Comment/uncomment in normal/debug mode...
//var myLogReader = new YAHOO.widget.LogReader();

ORBEON.widgets.datatable.datatables = {
};
