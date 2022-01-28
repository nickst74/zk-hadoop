disable_selectors();

var all_data;

/* Filter variables */
var past_events_f = false;
var healthy_f = true;
var corrupted_f = true;
var dnode_addr_f = "All";
var datetime_type_f = "All";
var from_datetime_f, to_datetime_f;

/**** Just initialize datetime pickers  ****/
$('#from-datetime').datetimepicker();
$('#to-datetime').datetimepicker({
    useCurrent: false //Important! See issue #1075
});
/*******************************************/

function disable_selectors() {
    const selectors = document.querySelectorAll('.selector');
    selectors.forEach(selector => selector.disabled = true);
}

function enable_selectors() {
    const selectors = document.querySelectorAll('.selector');
    selectors.forEach(selector => selector.disabled = false);
}

/**
 * Parsing function for BlockReport events from blockchain
 * Specific for Block Report Dashboard use
 * @param {list} events List of BlockReport events 
 * @returns Formatted Reports
 */
function parse_events(events) {
    const data = [];
    const blockchain_addresses = new Set();
    events.forEach(event => {
        const bc_addr = event.returnValues.datanode.toLowerCase();
        blockchain_addresses.add(bc_addr);
        const ip = bc_to_ip[bc_addr];
        const time = format_time(event.returnValues.time);
        const time_dnode = time + ' @ ' + (ip === undefined ? 'N/A' : ip) + '\n' + bc_addr;
        event.returnValues.blocks.forEach(blockId => {
            const file = block_to_file[blockId];
            var blockIdx, fileBlocks;
            if(file !== undefined) {
                blockIdx = file_to_blocklist[file].indexOf(blockId) + 1;
                fileBlocks = file_to_blocklist[file].length;
            }
            const wrong = event.returnValues.corrupted.includes(blockId);
            data.push({
                'time_dnode': time_dnode,
                'blockId': blockId,
                'file': file,
                'blockIdx': blockIdx,
                'fileBlocks': fileBlocks,
                'wrong': wrong
            });
        });
    });
    bc_addresses = Array.from(blockchain_addresses);
    return data;
}


/*  CHART CREATION AND CUSTOMIZATION  */
am4core.ready(function() {

    am4core.useTheme(am4themes_animated);

    chart = am4core.create("chartdiv", am4charts.XYChart);
    chart.maskBullets = false;

    var xAxis = chart.xAxes.push(new am4charts.CategoryAxis());
    var yAxis = chart.yAxes.push(new am4charts.CategoryAxis());

    xAxis.dataFields.category = "blockId";
    yAxis.dataFields.category = "time_dnode";

    xAxis.renderer.grid.template.disabled = true;
    xAxis.renderer.minGridDistance = 100;
    xAxis.renderer.cellStartLocation = 0.2;
    xAxis.renderer.cellEndLocation = 0.8;

    yAxis.renderer.grid.template.disabled = true;
    yAxis.renderer.inversed = false;
    yAxis.renderer.minGridDistance = 30;
    yAxis.renderer.cellStartLocation = 0.2;
    yAxis.renderer.cellEndLocation = 0.8;

    var series = chart.series.push(new am4charts.ColumnSeries());
    series.dataFields.categoryX = "blockId";
    series.dataFields.categoryY = "time_dnode";
    series.dataFields.wrong = "wrong";
    series.dataFields.file = "file";
    series.sequencedInterpolation = true;
    series.defaultState.transitionDuration = 2000;

    // adapters for the color filling and opacity of the items
    series.columns.template.adapter.add('fill', (fill, target) => {
        if(target.dataItem) {
            if(target.dataItem.file !== undefined) {
                return am4core.color(target.dataItem.wrong ? '#C41919' : '#008000');
            }
            return am4core.color(target.dataItem.wrong ? '#ff8181' : '#88ee88');
        }
        return fill;
    });

    // Add a drop shadow filter on columns
    var shadow = series.columns.template.filters.push(new am4core.DropShadowFilter);
    shadow.opacity = 0.1;

    // Create hover state
    var hoverState = series.columns.template.states.create("hover");
    hoverState.properties.fill = am4core.color("#396478");
    hoverState.properties.dx = -5;
    hoverState.properties.dy = -5;

    // Slightly shift the shadow and make it more prominent on hover
    var hoverShadow = hoverState.filters.push(new am4core.DropShadowFilter);
    hoverShadow.dx = 6;
    hoverShadow.dy = 6;
    hoverShadow.opacity = 0.3;

    series.columns.template.events.on('hit', function(ev) {
        // TODO: check if the file exists in the hdfs
        if(ev.target._dataItem._dataContext.file !== undefined) {
            var filename = ev.target._dataItem._dataContext.file;
            window.location.href = "explorer.html#" + filename;
        }
    });

    var bgColor = new am4core.InterfaceColorSet().getFor("background");
    var columnTemplate = series.columns.template;
    columnTemplate.cursorOverStyle = am4core.MouseCursorStyle.pointer;
    columnTemplate.strokeWidth = 1;
    columnTemplate.strokeOpacity = 0.2;
    columnTemplate.stroke = bgColor;
    //columnTemplate.propertyFields.fill = "color";
    columnTemplate.column.cornerRadius(6, 6, 6, 6);
    columnTemplate.adapter.add('tooltipHTML', (tooltipHTML, target) => {
        if(target) {
            var ret = '<center><strong>Block #{blockId}</strong></center>';
            if(target.dataItem.file !== undefined) {
                return ret + '<center>Block <strong>{blockIdx}/{fileBlocks}</strong> of file <span style="font-family:monospace">{file}</span></center>';
            }
            return ret + "<center>File N/A</center>";
        }
        return tooltipHTML;
    })
    columnTemplate.width = am4core.percent(80);
    columnTemplate.height = am4core.percent(80);

    series.heatRules.push({
        target: columnTemplate,
        property: "fill",
    });

    // Add scrollbar
    function add_scrollbar(axis) {
        axis.startGrip.background.fill = am4core.color("#CBA5A4");
        axis.endGrip.background.fill = am4core.color("#CBA5A4");
        axis.thumb.background.fill = am4core.color("#CBA5A4");

        axis.startGrip.icon.stroke = am4core.color("#8A5658");
        axis.endGrip.icon.stroke = am4core.color("#8A5658");

        // Applied on hover
        axis.startGrip.background.states.getKey("hover").properties.fill = am4core.color("#BC8C8A");
        axis.endGrip.background.states.getKey("hover").properties.fill = am4core.color("#BC8C8A");
        axis.thumb.background.states.getKey("hover").properties.fill = am4core.color("#BC8C8A");

        // Applied on mouse down
        axis.startGrip.background.states.getKey("down").properties.fill = am4core.color("#AD7371");
        axis.endGrip.background.states.getKey("down").properties.fill = am4core.color("#AD7371");
        axis.thumb.background.states.getKey("down").properties.fill = am4core.color("#AD7371");
    }

    var cellSize = 20;
    chart.events.on('datavalidated', function(ev) {
        // Get objects of interest
        var chart = ev.target;
        var categoryAxis = chart.yAxes.getIndex(0);
        // Calculate how we need to adjust chart height
        var adjustHeight = chart.data.length * cellSize - categoryAxis.pixelHeight;
        // get current chart height
        var targetHeight = chart.pixelHeight + adjustHeight;
        // Set it on chart's container
        chart.svgContainer.htmlElement.style.height = targetHeight + 'px';
    });

    // Get events at startup and add the to chart
    blockchain(parse_events).then(data => {
        // init data
        all_data = data;
        // fix datanode selector with fetched data
        add_dnode_selector_options();
        // add chart scrollbar
        chart.scrollbarX = new am4core.Scrollbar();
        add_scrollbar(chart.scrollbarX);
        // init chart
        update_chart();
    });

});
/* END OF CHART CREATION STAFF */

/**** UPDATE CHART DATA ****/
function update_chart() {
    disable_selectors();
    /* A filter function */
    const myFilter = item => {
        // filter by file existence
        if(!past_events_f && item['file'] === undefined)
            return false;
        // filter by datatype
        if(!(item['wrong'] ? corrupted_f : healthy_f))
            return false;
        // filter by datanode ip or bc address
        if(dnode_addr_f !== "All" && dnode_addr_f !== item['time_dnode'].split(/[\n\ ]/)[4] && dnode_addr_f !== item['time_dnode'].split(/[\n\ ]/)[5])
            return false;
        // filter by date-time
        const date = new Date(item['time_dnode'].split(' @ ')[0]);
        switch (datetime_type_f) {
            case 'All':
                return true;
            case 'Date':
                return (from_datetime_f !== undefined &&
                        from_datetime_f.getFullYear() === date.getFullYear() &&
                        from_datetime_f.getMonth() === date.getMonth() &&
                        from_datetime_f.getDate() === date.getDate());
            case 'Datetime Range':
                return (from_datetime_f !== undefined &&
                        to_datetime_f !== undefined &&
                        from_datetime_f <= date &&
                        to_datetime_f >= date);
            default:
                return false;
        }
    };
    chart.data = all_data.filter(myFilter);
    enable_selectors();
}
/**** END OF UPDATE CHART DATA ****/

/**** DEFINE EVENT/FILTER LISTENERS ****/
document.getElementById("past-events-checkbox").addEventListener("change", e => {
    past_events_f = e.target.checked;
    update_chart();
});

document.getElementById("healthy-checkbox").addEventListener("change", e => {
    healthy_f = e.target.checked;
    update_chart();
});

document.getElementById("corrupt-checkbox").addEventListener("change", e => {
    corrupted_f = e.target.checked;
    update_chart();
});

document.getElementById("dnode-selector").addEventListener("change", e => {
    document.getElementById("dnode-selector-div").style.width = (e.target.value.length * 0.52 +4.2)+'em';
    dnode_addr_f = e.target.value;
    update_chart();
});

document.getElementById("datetime-selector").addEventListener("change", e => {
    document.getElementById("datetime-selector-div").style.width = (e.target.value.length * 0.52 +4.2)+'em';
    datetime_type_f = e.target.value;
    update_chart();
});

$("#from-datetime").on("dp.change", function (e) {
    from_datetime_f = new Date(e.date);
    if(datetime_type_f != 'All'){
        update_chart();
    }
});
$("#to-datetime").on("dp.change", function (e) {
    to_datetime_f = new Date(e.date);
    if(datetime_type_f == 'Datetime Range') {
        update_chart();
    }
});
/**** END OF DEFINE EVENT/FILTER LISTENERS ****/

/**** INIT DATANODE SELECTION DROPDOWN****/
function add_dnode_selector_options() {
    const selector = document.getElementById("dnode-selector");
    // create IP optgroup
    if(datanodes.length > 0) {
        const ip_group = document.createElement("optgroup");
        ip_group.label = "IP";
        datanodes.forEach(dnode => {
            const elem = document.createElement("option");
            elem.textContent = dnode;
            elem.disabled = true;
            elem.setAttribute("class", "selector");
            ip_group.appendChild(elem);
        });
        selector.appendChild(ip_group);
    }
    // create BC optgroup
    if(bc_addresses.length > 0) {
        const bc_group = document.createElement("optgroup");
        bc_group.label = "Blockchain";
        bc_addresses.forEach(dnode => {
            const elem = document.createElement("option");
            elem.textContent = dnode;
            elem.disabled = true;
            elem.setAttribute("class", "selector");
            bc_group.appendChild(elem);
        });
        selector.appendChild(bc_group);
    }
};
/**** END OF INIT DATANODE SELECTION DROPDOWN****/