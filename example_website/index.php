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
    <script src="https://ajax.googleapis.com/ajax/libs/jquery/3.7.1/jquery.min.js"></script>
    <title>AE2</title>
</head>
<body>
<h1>UNIVERSAL WEB TERMINAL</h1>

<section id="overlay">
    <section id="overlaytext">
        LOADING...
    </section>
</section>

<section id="terminalcontainer">
    <section id="terminaltypes">
        <section id='terminalOptions'>
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
        <section id='terminalCPUList' style='display: none;'>...</section>
        <section id='terminalCPUListForJob' style='display: none;'>...</section>
    </section>
    <section id="terminalwindow">
        <section id="terminalheader">
            <section id="terminalTerminalHeader">
                Terminal <input type="text" id="searchtext" placeholder="Search string" onkeyup="searchStringChanged(this);">
                <button onclick='openCraftingStatus();'>Crafting</button>
                <button onclick='refreshTerminal();'>Refresh</button>
            </section>
            <section id="terminalCPUHeader" style="display: none;">
                <span id="terminalCPUHeaderText"></span>
                <button onclick='closeCraftingStatus();'>Terminal</button>
                <button onclick='refreshTerminal();'>Refresh</button>
            </section>
            <section id='terminalJobHeader' style='display: none;'>
                <span id="terminalJobHeaderText"></span>
                <button onclick='cancelCurrentJob();' class='redtext'>Cancel</button>
            </section>
        </section>
        <section id="terminalcontent"></section>
    </section>
    <section id="terminalsubcontainer">
        <section id="terminalsubcontainersettings">
            <header>Settings</header>
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
        </section>
    </section>
</section>

<script>
    globalItemList = {};
    globalCPUList = {};
    currentWindow = 0; // 0 - main, 1 - CPU, 2 - Order screen
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
    }
    const screens = [
        ["terminalOptions", "terminalTerminalHeader"],
        ["terminalCPUList", "terminalCPUHeader"],
        ["terminalCPUListForJob", "terminalJobHeader"],
    ];
    const sortByDisplay = ['A-Z', '# of items', 'Mod'];
    const sortOrderDisplay = ['Ascending', 'Descending'];
    const storedCraftableDisplay = ['Stored', 'Craftable', 'Stored and craftable'];
    const itemsTypeDisplay = ['Items', 'Fluid drops', 'Items and fluid drops'];
    currentJob = -1;
    function searchStringChanged(el){
        let text = el.value;
        filteringOptions.searchString = text.toLowerCase();
        displayItemList();
    }
    function sortItemList(){
        let sortOrder = sortingOptions.sortOrder == 1 ? -1 : 1;
        if (sortingOptions.sortBy == 0){
            globalItemList.sort(function(i1,i2){
                return i1['itemname'].localeCompare(i2['itemname']) * sortOrder;
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
    function refreshTerminal(){
        if (currentWindow == 0)
            getItemList();
        else if (currentWindow == 1)
            displayCPUDetails();
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
        document.getElementById('sortByButton').innerHTML = sortByDisplay[sortingOptions.sortBy];
        document.getElementById('storedCraftableButton').innerHTML = storedCraftableDisplay[filteringOptions.storedCraftable];
        document.getElementById('itemsFluidsButton').innerHTML = itemsTypeDisplay[filteringOptions.itemsType];
        document.getElementById('sortOrderButton').innerHTML = sortOrderDisplay[sortingOptions.sortOrder];
        document.getElementById('autorefresh').checked = settings.autoRefresh;
        document.getElementById('itemsperrow').value = settings.itemsPerRow;
        document.getElementById('numberformat').value = settings.numberFormat;
    }
    initSettings();
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
    function displayCPUList(){
        let html = "";
        for (let key in globalCPUList){
            let cluster = globalCPUList[key];
            html += "<button onclick='selectCPU(this);' name='" + key + "' ";
            if (selectedCPU == key)
                html += "class='selected'";
            html += ">" + key;
            if (cluster['finalOutput'])
                html += " - " + cluster['finalOutput']['itemname'] + " x" + cluster['finalOutput']['quantity'];
            html += "</button>";
        }
        document.getElementById('terminalCPUList').innerHTML = html;
    }
    function displayCPUDetails(){
        let message = "Asking for " + selectedCPU + "...";
        pushLoadingScreen(message);
        $.getJSON('get?cpu=' + encodeURIComponent(selectedCPU).replace(/'/g,"%27").replace(/"/g,"%22"), function(data){
            console.log(data);
            let html = "";
            if (data['finalOutput'])
                document.getElementById("terminalCPUHeaderText").innerHTML =
                    selectedCPU + ": Crafting " + data['finalOutput']['itemname'] + " x" + data['finalOutput']['quantity'] + "<button onclick='cancelJobOnCPU(\"" + selectedCPU + "\");' class='redtext'>Cancel</button>";
            else
                document.getElementById("terminalCPUHeaderText").innerHTML = selectedCPU + ": Idle";

            html += "<table><tr>";
            let grid_i_max = settings.itemsPerRow;
            let grid_i = 0;
            if(data['items']){
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
                    html += "<td class='" + el + "'>" + item['itemname'] + "<br>Crafting: " + formatNumber(item['active']) + "<br>Scheduled: " + formatNumber(item['pending']) + "<br>Stored: " + formatNumber(item['stored']) + "</td>";
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
    function updateCPUList(){
        $.getJSON('list', function(data) {
            clusters = data['clusters'];
            globalCPUList = clusters;
            if(!globalCPUList[selectedCPU])
                selectedCPU = Object.keys(globalCPUList)[0];
            displayCPUList();
        });
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
    function displayItemList(){
        let html = "<table>";
        let grid_i_max = settings.itemsPerRow;
        let grid_i = 0;
        html += "<tr>";
        let items = globalItemList;
        for(let i = 0; i < items.length; i++){
            let item = items[i];
            if (!shouldDisplay(item))
                continue;
            html += "<td class='storage'>" + item['itemname'] + "<br>Stored: " + formatNumber(item['quantity']) + (item['craftable'] ? '<br><button onclick="beginOrderingItem(' + item['hashcode'] + ');">order</button>' : '') + "</td>";
            grid_i++;
            if(grid_i == grid_i_max){
                html += "</tr><tr>";
                grid_i = 0;
            }
        }
        for(; grid_i < grid_i_max; grid_i ++){
            html += "<td></td>";
        }
        html += "</tr>";
        document.getElementById("terminalcontent").innerHTML = html;
    }
    function getItemList(){
        let message = "Asking for item list...";
        pushLoadingScreen(message);
        $.getJSON('items', function(data){
            console.log(data);
            if (data['items']){
                globalItemList = data['items'];
                sortItemList();
                displayItemList();
            }
            popLoadingScreen(message);
        });
    }

    function beginOrderingItem(hashcode){
        console.log(hashcode);
        let quantity = Number(window.prompt("How much to order?", "1"));
        if (quantity == null || quantity == NaN || quantity < 0 || quantity > Math.pow(2,31)-1){
            return;
        }
        else {
            let message = "Sending order...";
            pushLoadingScreen(message);
            $.getJSON('order?item=' + hashcode + "&quantity=" + quantity, function(data){
                console.log(data);
                if (data['jobID']){
                    setCurrentScreen(2);
                    document.getElementById("terminalcontent").innerHTML = ";)";
                    document.getElementById("terminalJobHeaderText").innerHTML = "Calculating, please wait...";
                    currentJob = data['jobID'];
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
        $.getJSON('job?id=' + currentJob, function(data){
            if(currentWindow != 2){
                return;
            }
            console.log(data);
            if (data['jobIsDone']){
                let html = "";
                let jobData = data['jobData'];
                let htmlHeader = "";
                if (jobData['isSimulating']){
                    htmlHeader += "[Simulation :(] ";
                }
                htmlHeader += "Crafting Plan - " + jobData['bytesTotal'] + "bytes";
                if (!jobData['isSimulating']){
                    htmlHeader += "<button onclick='startCurrentJob();'>Start</button>";
                }
                document.getElementById("terminalJobHeaderText").innerHTML = htmlHeader;
                if (jobData['plan']){
                    html += "<table><tr>";
                    let grid_i_max = settings.itemsPerRow;
                    let grid_i = 0;
                    let items = jobData['plan'];
                    for(let i = 0; i < items.length; i++){
                        let item = items[i];

                        html += "<td class='" + (item['missing'] > 0 ? 'missing' : 'storage') + "'>" + item['itemname'];
                        if (item['missing'] > 0)
                            html += "<br>Missing: " + formatNumber(item['missing']);
                        if (item['requested'] > 0)
                            html += "<br>To craft: " + formatNumber(item['requested']);
                        if (item['steps'] > 0)
                            html += "<br>Steps: " + formatNumber(item['steps']);
                        if (item['stored'] > 0)
                            html += "<br>Available: " + formatNumber(item['stored']);
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
        $.getJSON('job?id=' + currentJob + "&cancel", function(data){

        });
    }
    function startCurrentJob(){
        if (currentWindow != 2){
            return;
        }
        let message = "Submitting job...";
        pushLoadingScreen(message);
        $.getJSON('job?id=' + currentJob + "&submit", function(data){
            popLoadingScreen(message);
            if (data['jobSubmissionFailureMessage']){
                window.alert(data['jobSubmissionFailureMessage']);
            }
            setCurrentScreen(0);
            refreshTerminal();
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

    function autoRefresher(){
        setTimeout(autoRefresher, 10000);
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

</script>
<br><br>
<footer>
This service is hosted by <a href="https://github.com/kuba6000/AE2-Web-Integration">AE2 Web Integration</a> Made by <a href="https://github.com/kuba6000">@kuba6000</a>
</footer>
</body>
</html>
