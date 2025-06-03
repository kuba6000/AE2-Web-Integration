<?php
    // AE2 Server endpoint (http://host:port/)
    $AE2_SERVER_HOST = "http://my.minecraftserver.com:2324/"; // slash at the end is important
    // AE2 Server password (MUST BE THE SAME AS ON THE SERVER)
    $AE2_SERVER_PASSWORD = "";

    // basic authoarization (the same authoarization as on the ae server)
    if (!isset($_SERVER['PHP_AUTH_USER']) || $_SERVER['PHP_AUTH_PW'] != $AE2_SERVER_PASSWORD) {
        header('WWW-Authenticate: Basic realm="AE2 Panel, please login"');
        header('HTTP/1.0 401 Unauthorized');
        exit;
    }

    // API proxy
    if (isset($_GET['API'])){
        $api_path = $_GET['API'];
        unset($_GET['API']);
        $params = $_GET;

        $ch = curl_init($AE2_SERVER_HOST . $api_path . '?' . http_build_query($params));
        curl_setopt($ch, CURLOPT_HTTPHEADER, array('Content-Type: application/xml'));
        curl_setopt($ch, CURLOPT_HEADER, FALSE);
        curl_setopt($ch, CURLOPT_USERPWD, 'a:' . $AE2_SERVER_PASSWORD); // authoarization is required on every request
        curl_setopt($ch, CURLOPT_TIMEOUT, 30);
        curl_setopt($ch, CURLOPT_RETURNTRANSFER, TRUE);
        $return = curl_exec($ch);
        curl_close($ch);

        print($return);

        exit;
    }

    // website
?>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <link rel="stylesheet" href="style.css">
    <link rel="icon" type="image/x-icon" href="favicon.ico" />
    <script src="https://ajax.googleapis.com/ajax/libs/jquery/3.7.1/jquery.min.js"></script>
    <script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
    <title>AE2</title>
</head>
<body>
<section id="topMessagesContainer">
    <section id="topMessages">

    </section>
</section>
<h1>UNIVERSAL WEB TERMINAL</h1>
<section id="overlay">
    <section id="overlaytext">
        LOADING...
    </section>
</section>
<section id="terminalcontainer">
    <section id="terminaltypes">
        <section id='terminalOptions'>
            <button class='collapsible mobile-only'>Terminal options</button>
            <section>
                <button onclick='changeSortingBy(this);' id='sortByButton'>initializing</button>
                <button onclick='changeStoredCraftable(this);' id='storedCraftableButton'>initializing</button>
                <button onclick='changeItemsFluids(this);' id='itemsFluidsButton'>initializing</button>
                <button onclick='changeSortOrder(this);' id='sortOrderButton'>initializing</button>
                <!-- <button>NEI Search?</button> -->
                <!-- <button>Terminal style?</button> -->
                <button onclick='alert("Coming soon");'>Crafting terminal</button>
                <button onclick='alert("Coming soon");'>Pattern terminal</button>
                <button onclick='alert("Coming soon");'>Fluid terminal</button>
                <button onclick='alert("Coming soon");'>Interface terminal</button>
                <button onclick='alert("Coming soon");'>Level terminal</button>
                <button onclick='alert("Coming soon");'>Essentia terminal</button>
            </section>
        </section>
        <section id='terminalCPUList' style='display: none;'>...</section>
        <section id='terminalCPUListForJob' style='display: none;'>...</section>
        <section id='terminalHistoryDetails' style='display: none;'>...</section>
    </section>
    <section id="terminalwindow">
        <section id="terminalheader">
            <section id="terminalTerminalHeader">
                Terminal <input type="text" id="searchtext" placeholder="Search string" onkeyup="searchStringChanged(this);">
                <button onclick='openCraftingStatus();'>Crafting</button>
                <button onclick='openCraftingHistory();'>Crafting History</button>
                <button onclick='refreshTerminal();'>Refresh</button>
            </section>
            <section id="terminalCPUHeader" style="display: none;">
                <span id="terminalCPUHeaderText"></span>
                <button onclick='closeCraftingStatus();'>Terminal</button>
                <button onclick='refreshTerminal();'>Refresh</button>
                <button onclick='cancelJobOnCPU(selectedCPU);' class='redtext'>Cancel</button>
            </section>
            <section id='terminalJobHeader' style='display: none;'>
                <span id="terminalJobHeaderText"></span>
                <button onclick='cancelCurrentJob();' class='redtext'>Cancel</button>
            </section>
            <section id='terminalHistoryHeader' style='display: none;'>
                <span id="terminalHistoryHeaderText"></span>
                <button onclick='closeCraftingStatus();'>Terminal</button>
                <button onclick='getCraftingHistory();' id="terminalHistoryHeaderRefresh">Refresh</button>
            </section>
        </section>
        <section id="terminalcontent"></section>
    </section>
    <section id="terminalsubcontainer">
        <section id="terminalsubcontainersettings">
            <header class='mobile-hidden'>Settings</header>
            <button class='collapsible mobile-only'>Settings</button>
            <section>
                <input type="checkbox" name="autorefresh" id="autorefresh" onchange="changeAutoRefresh(this);"> <label for='autorefresh'>Automatically refresh current screen</label>
                <br>
                <input type="number" name="itemsperrow" id="itemsperrow" min=1 max=8 value=5 onchange="changeItemsPerRow(this);"> <label for='itemsperrow'>Items per row</label>
                <br>
                <select name="numberformat" id="numberformat" onchange="changeNumberFormat(this);">
                    <option value=0>Local</option>
                    <option value=1 selected>EN-US</option>
                    <option value=2>Compact</option>
                    <option value=3>Scientific</option>
                    <option value=4>No format</option>
                </select>
                <label for="numberformat"> Number format</label>
                <br>
                <input type="checkbox" name="showitemid" id="showitemid" onchange="changeShowItemID(this);"> <label for="showitemid">Show item id</label>
                <br>
                <input type="checkbox" name="showitemicon" id="showitemicon" onchange="changeShowItemIcon(this);" disabled title="Work in progress"> <label for="showitemicon" title="Work in progress">Show item icons [work in progress]</label>
                <br>
                <button onclick="if(confirm('Are you sure you want to purge icons cache? This will result all icons to be redownloaded!')){ localStorage.clear(); location.reload(); }">Purge icon cache</button>
            </section>
        </section>
    </section>
</section>

<script>
    const isOutdated = false;//_REPLACE_ME_VERSION_OUTDATED; <?php // TODO: NO IMPLEMENTATION ?>
    globalItemList = {};
    globalCPUList = {};
    currentWindow = 0; // 0 - main, 1 - CPU, 2 - Order screen, 3 - History window
    selectedCPU = 0;
    filteringOptions = {
        searchString: "",
        storedCraftable: 2, // 0 - stored, 1 - craftable, 2 - both
        itemsType: 2,       // 0 - items, 1 - fluids, 2 - both
    };
    sortingOptions = {
        sortBy: 0,          // 0 - AZ, 1 - number of items, 2 - mod
        sortOrder: 0,       // 0 - ascending, 1 - descending
    };
    settings = {
        autoRefresh: false,
        itemsPerRow: 5,
        numberFormat: 0,
        showItemID: false,
        showItemIcon: false
    }
    const screens = [
        ["terminalOptions", "terminalTerminalHeader"],
        ["terminalCPUList", "terminalCPUHeader"],
        ["terminalCPUListForJob", "terminalJobHeader"],
        ["terminalHistoryHeader", "terminalHistoryDetails"],
    ];
    const sortByDisplay = ['A-Z', '# of items', 'Mod'];
    const sortOrderDisplay = ['Ascending', 'Descending'];
    const storedCraftableDisplay = ['Stored', 'Craftable', 'Stored and craftable'];
    const itemsTypeDisplay = ['Items', 'Fluid drops', 'Items and fluid drops'];

    const BYTE_UNIT = [ "B", "KB", "MB", "GB", "TB", "PB", "EB", "ZB", "YB", "BB" ];
    const BYTE_LIMIT = [ 1.0, 1024.0, 1048576.0, 1.073741824E9, 1.099511627776E12, 1.125899906842624E15, 1.15292150460684698E18, 1.1805916207174113E21, 1.2089258196146292E24, 1.2379400392853803E27 ];

    currentJob = {
        id: -1,
        itemHash: -1,
        bytesTotal: -1,
    }
    function searchStringChanged(el){
        let text = el.value;
        filteringOptions.searchString = text.toLowerCase();
        displayItemList();
    }
    function sortItemList(){
        let sortOrder = sortingOptions.sortOrder == 1 ? -1 : 1;
        if (sortingOptions.sortBy == 0){
            globalItemList.sort(function(i1,i2){
                return skipSpecialFormat(i1['itemname']).localeCompare(skipSpecialFormat(i2['itemname'])) * sortOrder;
            });
        }
        else if(sortingOptions.sortBy == 1){
            globalItemList.sort(function(i1,i2){
                return (i1['quantity'] - i2['quantity']) * sortOrder;
            });
        }
        else if(sortingOptions.sortBy == 2){
            globalItemList.sort(function(i1,i2){
                let mod1 = i1['itemid'].substring(0, i1['itemid'].indexOf(':'));
                let mod2 = i2['itemid'].substring(0, i2['itemid'].indexOf(':'));
                return mod1.localeCompare(mod2) * sortOrder;
            });
        }
    }
    function shouldDisplay(item){
        if (filteringOptions.storedCraftable != 2
            && item['craftable'] != (filteringOptions.storedCraftable == 1)){
            return false;
        }
        if (filteringOptions.itemsType != 2
            && (item['itemid'] == 'ae2fc:fluid_drop:0') != (filteringOptions.itemsType == 1)){
            return false;
        }
        if (filteringOptions.searchString && filteringOptions.searchString != ""){
            return item['itemname'].toLowerCase().indexOf(filteringOptions.searchString) !== -1;
        }
        return true;
    }
    function changeSortingBy(el){
        sortingOptions.sortBy++;
        if (sortingOptions.sortBy > 2) sortingOptions.sortBy = 0;
        el.innerHTML = sortByDisplay[sortingOptions.sortBy];
        sortItemList();
        displayItemList();
        setCookie("sortBy", sortingOptions.sortBy, 7);
    }
    function changeStoredCraftable(el){
        filteringOptions.storedCraftable++;
        if (filteringOptions.storedCraftable > 2) filteringOptions.storedCraftable = 0;
        el.innerHTML = storedCraftableDisplay[filteringOptions.storedCraftable];
        displayItemList();
        setCookie("storedCraftable", filteringOptions.storedCraftable, 7);
    }
    function changeItemsFluids(el){
        filteringOptions.itemsType++;
        if (filteringOptions.itemsType > 2) filteringOptions.itemsType = 0;
        el.innerHTML = itemsTypeDisplay[filteringOptions.itemsType];
        displayItemList();
        setCookie("itemsType", filteringOptions.itemsType, 7);
    }
    function changeSortOrder(el){
        sortingOptions.sortOrder++;
        if (sortingOptions.sortOrder > 1) sortingOptions.sortOrder = 0;
        el.innerHTML = sortOrderDisplay[sortingOptions.sortOrder];
        sortItemList();
        displayItemList();
        setCookie("sortOrder", sortingOptions.sortOrder, 7);
    }
    function changeAutoRefresh(el){
        settings.autoRefresh = el.checked;
        setCookie("autoRefresh", settings.autoRefresh ? 1 : 0, 7);
    }
    function changeItemsPerRow(el){
        settings.itemsPerRow = el.value;
        if(settings.itemsPerRow < 1) settings.itemsPerRow = 1;
        if(settings.itemsPerRow > 8) settings.itemsPerRow = 8;
        setCookie("itemsPerRow", settings.itemsPerRow, 7);
        refreshDisplay();
    }
    function changeNumberFormat(el){
        settings.numberFormat = Number(el.value);
        if(settings.numberFormat < 0) settings.numberFormat = 0;
        if(settings.numberFormat > 4) settings.numberFormat = 4;
        setCookie("numberFormat", settings.numberFormat, 7);
        refreshDisplay();
    }
    function changeShowItemID(el){
        settings.showItemID = el.checked;
        setCookie("showItemID", settings.showItemID ? 1 : 0, 7);
        refreshDisplay();
    }
    function changeShowItemIcon(el){
        settings.showItemIcon = el.checked;
        setCookie("showItemIcon", settings.showItemIcon ? 1 : 0, 7);
        refreshDisplay();
    }
    function refreshTerminal() {
        if (currentWindow == 0)
            getItemList();
        else if (currentWindow == 1)
            displayCPUDetails();
        else if (currentWindow == 3) {
            if (!document.getElementById('toggleInterfaceShare'))
                getCraftingHistory();
        }
    }
    function refreshDisplay(){
        if (currentWindow == 0)
            displayItemList();
    }
    function setCookie(cname, cvalue, exdays) {
        const d = new Date();
        d.setTime(d.getTime() + (exdays * 24 * 60 * 60 * 1000));
        let expires = "expires="+d.toUTCString();
        document.cookie = cname + "=" + cvalue + ";" + expires + ";path=/";
    }
    function getCookie(cname) {
        let name = cname + "=";
        let ca = document.cookie.split(';');
        for(let i = 0; i < ca.length; i++) {
            let c = ca[i];
            while (c.charAt(0) == ' ') {
                c = c.substring(1);
            }
            if (c.indexOf(name) == 0) {
                return c.substring(name.length, c.length);
            }
        }
        return "";
    }
    function initSettings(){
        let cookie = getCookie("sortBy");
        if (cookie != "")
            sortingOptions.sortBy = Number(cookie);
        cookie = getCookie("storedCraftable");
        if (cookie != "")
            filteringOptions.storedCraftable = Number(cookie);
        cookie = getCookie("itemsType");
        if (cookie != "")
            filteringOptions.itemsType = Number(cookie);
        cookie = getCookie("sortOrder");
        if (cookie != "")
            sortingOptions.sortOrder = Number(cookie);
        cookie = getCookie("autoRefresh");
        if (cookie != "")
            settings.autoRefresh = Number(cookie) == 1;
        cookie = getCookie("itemsPerRow");
        if (cookie != "")
            settings.itemsPerRow = Number(cookie);
        cookie = getCookie("numberFormat");
        if (cookie != "")
            settings.numberFormat = Number(cookie);
        cookie = getCookie("showItemID");
        if (cookie != "")
            settings.showItemID = Number(cookie) == 1;
            cookie = getCookie("showItemIcon");
        if (cookie != "")
            settings.showItemIcon = Number(cookie) == 1;
        document.getElementById('sortByButton').innerHTML = sortByDisplay[sortingOptions.sortBy];
        document.getElementById('storedCraftableButton').innerHTML = storedCraftableDisplay[filteringOptions.storedCraftable];
        document.getElementById('itemsFluidsButton').innerHTML = itemsTypeDisplay[filteringOptions.itemsType];
        document.getElementById('sortOrderButton').innerHTML = sortOrderDisplay[sortingOptions.sortOrder];
        document.getElementById('autorefresh').checked = settings.autoRefresh;
        document.getElementById('itemsperrow').value = settings.itemsPerRow;
        document.getElementById('numberformat').value = settings.numberFormat;
        document.getElementById('showitemid').checked = settings.showItemID;
        document.getElementById('showitemicon').checked = settings.showItemIcon;
    }
    initSettings();
    function formatBytes(bytes) {
        for (let i = 1; i < 10; i++) {
            if (bytes < BYTE_LIMIT[i]) {
                return (bytes / BYTE_LIMIT[i - 1]) + " " + BYTE_UNIT[i - 1];
            }
        }
        return (bytes / BYTE_LIMIT[0]) + " " + BYTE_UNIT[0];
    }
    function formatNumber(num){
        switch (settings.numberFormat) {
            case 0:
                return num.toLocaleString();
            case 1:
                return num.toLocaleString('en-US');
            case 2:
                return Intl.NumberFormat('en-US', {
                    notation: "compact",
                    maximumFractionDigits: 3
                }).format(num);
            case 3:
                return num.toExponential(3);
            case 4:
            default:
                return num;
        }
    }
    function formatTime(time){
        let s = Number(time) / 1000;
        let format = 's';
        if(s > 60){
            s /= 60;
            format = 'm';
        }
        if(s > 60){
            s /= 60;
            format = 'h';
        }
        return Intl.NumberFormat('en-US', {
                    maximumFractionDigits: 3
                }).format(s) + format;
    }
    function formatPercent(percent){
        return Intl.NumberFormat('en-US', {
            style: "percent",
            maximumFractionDigits: 2
        }).format(percent);
    }
    const extraSpecialFormatChars = "klmno";
    const extraSpecialFormatResetChar = 'r';
    function parseSpecialFormat(itemName) {
        let formattedItemName = "";
        let spanCounter = 0;
        let extraSpecialCounter = 0;
        for (let i = 0; i < itemName.length; i++){
            char = itemName[i];
            if (char == '§'){
                let specialChar = itemName[++i];
                if (extraSpecialFormatChars.indexOf(specialChar) != -1) {
                    extraSpecialCounter++;
                }
                else if (specialChar == extraSpecialFormatResetChar) {
                    for (let j = 0; j < spanCounter; j++){
                        formattedItemName += "</span>";
                    }
                    spanCounter = 0;
                    extraSpecialCounter = 0;
                    continue;
                }
                else if (extraSpecialCounter > 0) {
                    for (let j = 0; j < extraSpecialCounter; j++){
                        formattedItemName += "</span>";
                    }
                    spanCounter -= extraSpecialCounter;
                    extraSpecialCounter = 0;
                }
                formattedItemName += "<span class='minecraftSpecialFormat_" + specialChar + "'>";
                spanCounter++;
            }
            else
            {
                formattedItemName += char;
            }
        }
        for (let i = 0; i < spanCounter; i++){
            formattedItemName += "</span>";
        }
        return formattedItemName;
    }
    function skipSpecialFormat(itemName) {
        if (itemName.indexOf('§') == -1){
            return itemName;
        }
        let skippedItemName = "";
        for (let i = 0; i < itemName.length; i++){
            char = itemName[i];
            if (char == '§'){
                i++;
                continue;
            }
            skippedItemName += char;
        }
        return skippedItemName;
    }
    function formatItemName(itemObject, allowNewLines=true) {
        let itemName = '<b>' + itemObject['itemname'] + '</b>';
        if (itemName.indexOf('§') != -1){
            itemName = parseSpecialFormat(itemName);
        }
        let html = itemName;
        if (settings.showItemID)
            html += (allowNewLines ? "<br>" : "") + " " + itemObject['itemid'];
        return html;
    }
    function displayCPUList(){
        let html = "";
        for (let key in globalCPUList){
            let cluster = globalCPUList[key];
            html += "<button onclick='selectCPU(this);' name='" + key + "' ";
            if (selectedCPU == key)
                html += "class='selected'";
            html += ">" + key;
            if (cluster['finalOutput'])
                html += " - " + formatItemName(cluster['finalOutput'], false) + " x" + cluster['finalOutput']['quantity'];
            html += "</button>";
        }
        document.getElementById('terminalCPUList').innerHTML = html;
    }
    function displayCPUDetails(){
        let message = "Asking for " + selectedCPU + "...";
        pushLoadingScreen(message);
        $.getJSON('get?cpu=' + encodeURIComponent(selectedCPU).replace(/'/g,"%27").replace(/"/g,"%22"), function(data){
            console.log(data);
            if (data.status !== "OK"){
                alert(data.status + ": " + data.data);
                popLoadingScreen(message);
                return;
            }
            data = data.data;
            let html = "";
            if (data['finalOutput'])
                document.getElementById("terminalCPUHeaderText").innerHTML =
                    selectedCPU + ": Crafting " + formatItemName(data['finalOutput'], false) + " x" + data['finalOutput']['quantity'];
            else
                document.getElementById("terminalCPUHeaderText").innerHTML = selectedCPU + ": Idle";
            let hasTrackingInfo = data['hasTrackingInfo'];
            html += "<table><tr>";
            let grid_i_max = settings.itemsPerRow;
            let grid_i = 0;
            if (data['items']){
                let items = data['items'];
                for(let i = 0; i < items.length; i++){
                    let item = items[i];
                    let el = '';
                    if(item['active'] > 0)
                        el = 'active';
                    else if(item['pending'] > 0)
                        el = 'pending';
                    else
                        el = 'storage';
                    html += "<td class='" + el + "'>" + formatItemName(item) + "<br>Crafting: " + formatNumber(item['active']) + "<br>Scheduled: " + formatNumber(item['pending']) + "<br>Stored: " + formatNumber(item['stored']);
                    if (hasTrackingInfo && item['timeSpentCrafting']){
                        html += "<br>Time: " + formatTime(item['timeSpentCrafting']) + " (" + formatPercent(item['shareInCraftingTimeCombined']) + ")";
                        html += "<br>Crafted total: " + formatNumber(item['craftedTotal']) + " (" + formatNumber(item['craftsPerSec']) + "/s)";
                    }
                    html += "</td>";
                    grid_i++;
                    if(grid_i == grid_i_max){
                        html += "</tr><tr>";
                        grid_i = 0;
                    }
                }
            }
            for(; grid_i < grid_i_max; grid_i ++){
                html += "<td></td>";
            }
            html += "</tr></table>";
            document.getElementById("terminalcontent").innerHTML = html;
            popLoadingScreen(message);
        });
    }
    cpuForJob = "";
    function updateCPUList(){
        $.getJSON('list', function(data) {
            if(data.status !== "OK"){
                alert(data.status + ": " + data.data);
                return;
            }
            data = data.data;
            clusters = data;
            globalCPUList = clusters;
            if (!globalCPUList[selectedCPU])
                selectedCPU = Object.keys(globalCPUList)[0];
            displayCPUList();
        });
    }
    function isValidCPUForOrder(cluster) {
        if (!cluster['finalOutput']) return true;
        if (currentJob.itemHash != cluster['finalOutput']['hashcode']) return false;
        if (cluster['usedStorage'] == -1) return false;
        return cluster['availableStorage'] >= cluster['usedStorage'] + currentJob.bytesTotal;
    }
    function updateCPUListForJob() {
        let html = "";
        for (let key in globalCPUList){
            let cluster = globalCPUList[key];
            html += "<button onclick='selectCPUForJob(this);' name='" + key + "' class='";
            if (cluster['finalOutput'])
            {
                if (cluster['usedStorage'] != -1 && currentJob.itemHash == cluster['finalOutput']['hashcode'] && cluster['availableStorage'] >= cluster['usedStorage'] + currentJob.bytesTotal){
                    html += "mergable";
                    if (!globalCPUList[cpuForJob])
                        cpuForJob = key;
                }
                else
                    html += "invalid";
            }
            else {
                if (!globalCPUList[cpuForJob])
                    cpuForJob = key;
            }
            if (cpuForJob == key)
                html += " selected";
            html += "'>" + key;
            if (cluster['finalOutput'])
                html += " - " + formatItemName(cluster['finalOutput'], false) + " x" + cluster['finalOutput']['quantity'];
            if (cluster['usedStorage'] && cluster['usedStorage'] != -1){
                html += "<br> " + formatBytes(cluster['usedStorage']) + " / " + formatBytes(cluster['availableStorage']);
            }
            else {
                html += "<br>" + formatBytes(cluster['availableStorage']);
            }
            html += "<br>" + cluster['coProcessors'] + " Co-Procs";
            html += "</button>";
        }
        document.getElementById('terminalCPUListForJob').innerHTML = html;
    }
    function selectCPUForJob(el) {
        let cluster = globalCPUList[el.name];
        if (!cluster) return;
        if (!isValidCPUForOrder(cluster)) return;
        cpuForJob = el.name;
        updateCPUListForJob();
    }
    function selectCPU(el) {
        selectedCPU = el.name;
        displayCPUList();
        displayCPUDetails();
    }
    function setCurrentScreen(screen){
        for (k of screens[currentWindow])
            document.getElementById(k).style.display = 'none';
        currentWindow = screen;
        for (k of screens[currentWindow])
            document.getElementById(k).style.display = 'block';
    }
    function openCraftingStatus(){
        setCurrentScreen(1);
        displayCPUDetails();
    }
    function closeCraftingStatus(){
        setCurrentScreen(0);
        getItemList();
    }
    function openCraftingHistory(){
        setCurrentScreen(3);
        getCraftingHistory();
    }
    function displayItemList(shouldFetchIcons = false){
        let html = "<table>";
        let grid_i_max = settings.itemsPerRow;
        let grid_i = 0;
        html += "<tr>";
        let items = globalItemList;
        let itemsNoIcon = [];
        for(let i = 0; i < items.length; i++){
            let item = items[i];
            if (!shouldDisplay(item))
                continue;
            let imgSrc = getIcon(item);
            if (imgSrc === null){
                itemsNoIcon.push(item);
                imgSrc = '';
            }
            else {
                imgSrc = "data:image/png;base64," + imgSrc;
            }
            if (settings.showItemIcon){
                html += "<td class='storage'>" + formatItemName(item) + "<img src='" + imgSrc + "' /><br>Stored: " + formatNumber(item['quantity']) + (item['craftable'] ? '<br><button onclick="beginOrderingItem(' + item['hashcode'] + ');">order</button>' : '') + "</td>";
            }
            else {
                html += "<td class='storage'>" + formatItemName(item) + "<br>Stored: " + formatNumber(item['quantity']) + (item['craftable'] ? '<br><button onclick="beginOrderingItem(' + item['hashcode'] + ');">order</button>' : '') + "</td>";
            }
            grid_i++;
            if(grid_i == grid_i_max){
                html += "</tr><tr>";
                grid_i = 0;
            }
        }
        for(; grid_i < grid_i_max; grid_i ++){
            html += "<td></td>";
        }
        html += "</tr></table>";
        document.getElementById("terminalcontent").innerHTML = html;
        if (settings.showItemIcon && shouldFetchIcons && itemsNoIcon.length > 0)
            fetchIcons(itemsNoIcon);
    }
    function getItemList(){
        let message = "Asking for item list...";
        pushLoadingScreen(message);
        $.getJSON('items', function(data){
            console.log(data);
            if(data.status !== "OK"){
                alert(data.status + ": " + data.data);
                popLoadingScreen(message);
                return;
            }
            data = data.data;
            globalItemList = data;
            sortItemList();
            displayItemList(true);
            popLoadingScreen(message);
        });
    }
    function getCraftingHistory(){
        let message = "Asking for tracking history list...";
        pushLoadingScreen(message);
        $.getJSON('trackinghistory', function(data){
            console.log(data);
            if(data.status !== "OK"){
                alert(data.status + ": " + data.data);
                popLoadingScreen(message);
                return;
            }
            data = data.data;
            let html = "<table>";
            for (let i = 0; i < data.length; i++){
                let trackingHistoryInfo = data[i];
                html += "<tr><td class='button' onclick='openTrackingData(" + trackingHistoryInfo['id'] + ");'>";
                html += "Job for " + formatItemName(trackingHistoryInfo['finalOutput'], false) + " x" + trackingHistoryInfo['finalOutput']['quantity'];
                if (trackingHistoryInfo['wasCancelled']){
                    html += " (was cancelled)";
                }
                html += "<br>Started " + new Date(Number(trackingHistoryInfo['timeStarted'])).toLocaleString();
                html += "<br>Completed " + new Date(Number(trackingHistoryInfo['timeDone'])).toLocaleString();
                html += "<br>Completed in " + formatTime(Number(trackingHistoryInfo['timeDone']) - Number(trackingHistoryInfo['timeStarted']));
                html += "</td></tr>";
            }
            html += "</table>";
            document.getElementById("terminalcontent").innerHTML = html;
            document.getElementById("terminalHistoryHeaderText").innerHTML = "Tracking history";
            document.getElementById("terminalHistoryHeaderRefresh").innerHTML = "Refresh";
            document.getElementById("terminalHistoryDetails").innerHTML = "...";
            popLoadingScreen(message);
        });
    }
    let isInterfaceChartInitialized = false;
    let isItemChartInitialized = false;
    let interfaceShareData = {};
    let itemShareData = {};
    function showInterfaceShare(){
        if(!isInterfaceChartInitialized){
            isInterfaceChartInitialized = true;
            let xValues = [];
            let interfaceShare = interfaceShareData;
            let dataSet = [];
            for (let i = 0; i < interfaceShare.length; i++){
                let AEInterface = interfaceShare[i];
                xValues.push(AEInterface['name']);
                let timings = AEInterface['timings'];
                for (let j = 0; j < timings.length; j++)
                {
                    let timing = timings[j];
                    if (dataSet.length <= j) {
                        dataSet.push({backgroundColor: "#3b4874", data: {}});
                    }
                    dataSet[j].data[AEInterface['name']] = [timing['started'], timing['ended']];
                }
            }

            console.log(dataSet);

            Chart.defaults.backgroundColor = '#9BD0F5';
            Chart.defaults.borderColor = '#EEE';
            Chart.defaults.color = '#EEE';

            new Chart("interfaceShareChart", {
                type: "bar",
                data: {
                    labels: xValues,
                    datasets: dataSet
                },
                options: {
                    plugins: {
                        legend: {
                            display: false
                        },
                        tooltip: {
                            callbacks: {
                                label: function(context) {
                                    let label = [];
                                    if (context.parsed._custom !== null) {
                                        label.push('From ' + new Date(context.parsed._custom.start).toLocaleString());
                                        label.push('To ' + new Date(context.parsed._custom.end).toLocaleString());
                                    }
                                    return label;
                                }
                            }
                        }
                    },
                    title: {
                        display: true,
                        text: "Interface share"
                    },
                    responsive: true,
                    scales: {
                        x: {
                            stacked: true,
                        },
                        y: {
                            beginAtZero: false,
                            ticks: {
                                callback: function(value, index, ticks) {
                                    return new Date(value).toLocaleString();
                                }
                            }
                        }
                    }
                }
            });
        }
        document.getElementById('toggleInterfaceShare').innerHTML = "Hide interface usage chart";
        document.getElementById('toggleInterfaceShare').onclick = hideInterfaceShare;
        document.getElementById("interfaceShareChart").style.display = 'block';
    }
    function hideInterfaceShare(){
        document.getElementById('toggleInterfaceShare').innerHTML = "Show interface usage chart";
        document.getElementById('toggleInterfaceShare').onclick = showInterfaceShare;
        document.getElementById("interfaceShareChart").style.display = 'none';
    }
    function showItemShare(){
        if (!isItemChartInitialized){
            isItemChartInitialized = true;
            let xValues = [];
            let itemShare = itemShareData;
            let dataSet = [];
            for (let i = 0; i < itemShare.length; i++){
                let item = itemShare[i];
                xValues.push(item['name']);
                let timings = item['timings'];
                for (let j = 0; j < timings.length; j++)
                {
                    let timing = timings[j];
                    if (dataSet.length <= j) {
                        dataSet.push({backgroundColor: "#3b4874", data: []});
                    }
                    dataSet[j].data.push([timing['started'], timing['ended']]);
                }
            }

            Chart.defaults.backgroundColor = '#9BD0F5';
            Chart.defaults.borderColor = '#EEE';
            Chart.defaults.color = '#EEE';

            new Chart("itemShareChart", {
                type: "bar",
                data: {
                    labels: xValues,
                    datasets: dataSet
                },
                options: {
                    plugins: {
                        legend: {
                            display: false
                        },
                        tooltip: {
                            callbacks: {
                                label: function(context) {
                                    let label = [];
                                    if (context.parsed._custom !== null) {
                                        label.push('From ' + new Date(context.parsed._custom.start).toLocaleString());
                                        label.push('To ' + new Date(context.parsed._custom.end).toLocaleString());
                                    }
                                    return label;
                                }
                            }
                        }
                    },
                    title: {
                        display: true,
                        text: "Item share"
                    },
                    responsive: true,
                    scales: {
                        x: {
                            stacked: true,
                        },
                        y: {
                            beginAtZero: false,
                            ticks: {
                                callback: function(value, index, ticks) {
                                    return new Date(value).toLocaleString();
                                }
                            }
                        }
                    }
                }
            });
        }
        document.getElementById('toggleItemShare').innerHTML = "Hide item crafting chart";
        document.getElementById('toggleItemShare').onclick = hideItemShare;
        document.getElementById("itemShareChart").style.display = 'block';
    }
    function hideItemShare(){
        document.getElementById('toggleItemShare').innerHTML = "Show item crafting chart";
        document.getElementById('toggleItemShare').onclick = showItemShare;
        document.getElementById("itemShareChart").style.display = 'none';
    }
    function openTrackingData(id){
        let message = "Asking for tracking data...";
        isInterfaceChartInitialized = false;
        isItemChartInitialized = false;
        pushLoadingScreen(message);
        $.getJSON('gettracking?id=' + id, function(data){
            console.log(data);
            if(data.status !== "OK"){
                alert(data.status + ": " + data.data);
                popLoadingScreen(message);
                return;
            }
            data = data.data;

            let html = "<button onclick='showInterfaceShare();' id='toggleInterfaceShare' style='width: 90%; font-size: 110%; margin: 10px 5%;'>Show interface usage chart</button><canvas id='interfaceShareChart' style='width:100%;max-width:100%;display:none;'></canvas>";
            html += "<button onclick='showItemShare();' id='toggleItemShare' style='width: 90%; font-size: 110%; margin: 10px 5%;'>Show item crafting chart</button><canvas id='itemShareChart' style='width:100%;max-width:100%;display:none;'></canvas>";

            html += "<table>";
            let grid_i_max = settings.itemsPerRow;
            let grid_i = 0;
            html += "<tr>";
            let items = data['items'];
            itemShareData = [];
            for(let i = 0; i < items.length; i++){
                let item = items[i];
                let name = skipSpecialFormat(item['itemname']);
                if (settings.showItemID)
                    name += " " + item['itemid'];
                itemShareData.push({'name': name, 'timings': item['timings']});

                html += "<td class='storage'>" + formatItemName(item, false) + " x" + formatNumber(item['craftedTotal']);
                html += "<br>Time: " + formatTime(item['timeSpentOn']) + " (" + formatPercent(item['shareInCraftingTimeCombined']) + ")";
                html += "<br>Efficiency: " + formatNumber(item['craftsPerSec']) + "/s";
                html += "</td>";
                grid_i++;
                if (grid_i == grid_i_max){
                    html += "</tr><tr>";
                    grid_i = 0;
                }
            }
            for(; grid_i < grid_i_max; grid_i ++){
                html += "<td></td>";
            }
            html += "</tr></table>";
            document.getElementById("terminalcontent").innerHTML = html;

            document.getElementById("terminalHistoryHeaderText").innerHTML = "Tracking history - " + formatItemName(data['finalOutput'], false) + " x" + formatNumber(data['finalOutput']['quantity']);
            document.getElementById("terminalHistoryHeaderRefresh").innerHTML = "Close";
            html = "";
            if(data['wasCancelled'])
            {
                html += "Was cancelled!<br>";
            }
            html += "Started:<br>- " + new Date(Number(data['timeStarted'])).toLocaleString();
            html += "<br>Completed:<br>- " + new Date(Number(data['timeDone'])).toLocaleString();
            html += "<br>Completed in " + formatTime(Number(data['timeDone']) - Number(data['timeStarted']));
            document.getElementById("terminalHistoryDetails").innerHTML = html;

            interfaceShareData = data['interfaceShare'];

            popLoadingScreen(message);
        });
    }
    function beginOrderingItem(hashcode){
        console.log(hashcode);
        let answer = window.prompt("How much to order?", "1");
        if (answer === null) // cancelled
            return;
        let quantity = Number(answer);
        if (quantity == null || quantity == NaN || quantity <= 0 || quantity > Math.pow(2,31)-1){
            return;
        }
        else {
            document.getElementById('terminalCPUListForJob').innerHTML = "...";
            let message = "Sending order...";
            pushLoadingScreen(message);
            $.getJSON('order?item=' + hashcode + "&quantity=" + quantity, function(data){
                console.log(data);
                if(data.status !== "OK"){
                    alert(data.status + ": " + data.data);
                    popLoadingScreen(message);
                    return;
                }
                data = data.data;
                if (data['jobID']){
                    setCurrentScreen(2);
                    document.getElementById("terminalcontent").innerHTML = ";)";
                    document.getElementById("terminalJobHeaderText").innerHTML = "Calculating, please wait...";
                    cpuForJob = "";
                    currentJob.id = data['jobID'];
                    currentJob.itemHash = hashcode;
                    setTimeout(updateCraftingPlan, 1000);
                }
                else{
                    //setCurrentScreen(0);
                }
                popLoadingScreen(message);
            });
        }
    }
    function updateCraftingPlan(){
        if(currentWindow != 2){
            return;
        }
        $.getJSON('job?id=' + currentJob.id, function(data){
            if(currentWindow != 2){
                return;
            }
            console.log(data);
            if (data.status !== "OK"){
                alert(data.status + ": " + data.data);
                return;
            }
            data = data.data;
            if (data['isDone']){
                let html = "";
                let htmlHeader = "";
                if (data['isSimulating']){
                    htmlHeader += "[Simulation :(] ";
                }
                htmlHeader += "Crafting Plan - " + data['bytesTotal'] + "bytes";
                currentJob.bytesTotal = data['bytesTotal'];
                if (!data['isSimulating']){
                    htmlHeader += "<button onclick='startCurrentJob();'>Start</button>";
                    updateCPUListForJob();
                }
                document.getElementById("terminalJobHeaderText").innerHTML = htmlHeader;
                if (data['plan']){
                    html += "<table><tr>";
                    let grid_i_max = settings.itemsPerRow;
                    let grid_i = 0;
                    let items = data['plan'];
                    for(let i = 0; i < items.length; i++){
                        let item = items[i];

                        html += "<td class='" + (item['missing'] > 0 ? 'missing' : 'storage') + "'>" + formatItemName(item);
                        if (item['missing'] > 0)
                            html += "<br>Missing: " + formatNumber(item['missing']);
                        if (item['requested'] > 0)
                            html += "<br>To craft: " + formatNumber(item['requested']);
                        if (item['steps'] > 0)
                            html += "<br>Steps: " + formatNumber(item['steps']);
                        if (item['stored'] > 0)
                            html += "<br>Available: " + formatNumber(item['stored']);
                        if (item['usedPercent'] > 0)
                            html += "<br>Used: " + formatPercent(item['usedPercent']);
                        html += "</td>";

                        grid_i++;
                        if(grid_i == grid_i_max){
                            html += "</tr><tr>";
                            grid_i = 0;
                        }
                    }
                    for(; grid_i < grid_i_max; grid_i ++){
                        html += "<td></td>";
                    }
                    html += "</tr></table>";
                }
                console.log(html);
                document.getElementById("terminalcontent").innerHTML = html;
            }
            else{
                setTimeout(updateCraftingPlan, 1000);
            }
        });
    }
    function cancelCurrentJob(){
        if(currentWindow != 2){
            return;
        }
        setCurrentScreen(0);
        refreshTerminal();
        $.getJSON('job?id=' + currentJob.id + "&cancel", function(data){
            if(data.status !== "OK"){
                alert(data.status + ": " + data.data);
                return;
            }
            data = data.data;
        });
    }
    function startCurrentJob(){
        if (currentWindow != 2){
            return;
        }
        let message = "Submitting job...";
        pushLoadingScreen(message);
        $.getJSON('job?id=' + currentJob.id + "&submit" + "&cpu=" + encodeURIComponent(cpuForJob).replace(/'/g,"%27").replace(/"/g,"%22"), function(data){
            if (data.status !== "OK"){
                alert(data.status + ": " + data.data);
            }
            popLoadingScreen(message);
            setCurrentScreen(0);
            refreshTerminal();
            updateCPUList();
        });
    }
    function cancelJobOnCPU(selectedCPU){
        if (selectedCPU == ""){
            return;
        }
        let message = "Cancelling job...";
        pushLoadingScreen(message);
        $.getJSON('cancelcpu?cpu=' + encodeURIComponent(selectedCPU).replace(/'/g,"%27").replace(/"/g,"%22"), function(data){
            updateCPUList();
            refreshTerminal();
            popLoadingScreen(message);
        });
    }

    function getIcon(item) {
        let data = localStorage.getItem("itemIcon" + item['hashcode']);
        if (data === null)
            return null;
        return data;
    }

    function fetchIcons(items) {
        let par = '';
        for(let i = 0; i < items.length; i++){
            par += items[i]['hashcode'] + ',';
        }
        $.getJSON('icon?items=' + par, function(data){
            if (data.status !== "OK"){
                alert(data.status + ": " + data.data);
                return;
            }
            data = data.data;
            console.log(data);
            let items = data;
            for(let i = 0; i < items.length; i++){
                localStorage.setItem("itemIcon" + items[i]['hashcode'], items[i]['pngData']);
            }
            refreshDisplay();
        });
    }

    function autoRefresher(){
        setTimeout(autoRefresher, 10000);
        if (loadingMessages.length > 0)
            return;
        updateCPUList();
        if (settings.autoRefresh){
            refreshTerminal();
        }
    }
    setTimeout(autoRefresher, 10000);

    loadingMessages = [];

    function pushLoadingScreen(message){
        loadingMessages.push(message);
        updateLoadingDisplay();
    }
    function popLoadingScreen(message){
        let i = loadingMessages.indexOf(message);
        if (i > -1){
            loadingMessages.splice(i, 1);
        }
        updateLoadingDisplay();
    }

    function updateLoadingDisplay(){
        if (loadingMessages.length > 0){
            document.getElementById('overlay').style.display = 'block';
            let html = "Please wait<br>";
            for(let message of loadingMessages){
                html += message + '<br>';
            }
            document.getElementById('overlaytext').innerHTML = html;
        }
        else
            document.getElementById('overlay').style.display = 'none';
    }

    updateCPUList();
    getItemList();

    function showAlertIfOutdated() {
        if (isOutdated){
            if (getCookie("DoNotShowUpdateMessage") == 1) return;
            pushTopMessage("<span>New version detected! Consider updating from <a href='https://github.com/kuba6000/AE2-Web-Integration/releases/'>github</a></span> <button onclick='closeTopMessage(this.parentElement);'>Close</button><button onclick='updateDoNotShowAgain(this.parentElement);'>Hide for 7 days</button><br style='clear: both;'/>");
        }
        else
        {
            setCookie("DoNotShowUpdateMessage", 0, 0);
        }
    }
    setTimeout(showAlertIfOutdated, 100);

    function updateDoNotShowAgain(el){
        setCookie("DoNotShowUpdateMessage", 1, 7);
        closeTopMessage(el);
    }

    topMessages = [];
    function pushTopMessage(message){
        topMessages.push(message);
        updateTopMessages();
    }

    function popTopMessage(i){
        topMessages.splice(i, 1);
        updateTopMessages();
    }

    function closeTopMessage(el){
        let message = el.getAttribute('topMessageID');
        popTopMessage(message);
    }

    function updateTopMessages(){
        let html = "";
        let i = 0;
        for(let message of topMessages){
            html += '<section topMessageID=' + i + '>' + message + '</section>';
            i++;
        }
        document.getElementById('topMessages').innerHTML = html;

        $("#topMessagesContainer").height($("#topMessages").height());
    }


    // make collapsible work
    let coll = document.getElementsByClassName("collapsible");

    for (let i = 0; i < coll.length; i++) {
        coll[i].addEventListener("click", function() {
            this.classList.toggle("active");
            var content = this.nextElementSibling;
            if (content.style.display === "block") {
                content.style.display = "none";
            } else {
                content.style.display = "block";
            }
        });
    }

</script>
<br><br>
<footer>
    This service is hosted using <a href="https://github.com/kuba6000/AE2-Web-Integration">AE2 Web Integration</a> Made by <a href="https://github.com/kuba6000">@kuba6000</a>
</footer>
</body>
</html>
