function copyToClipboard() {
    const copyText = document.getElementById("downloadLink");
    const copyButton = document.querySelector(".copyButton");
    navigator.clipboard.writeText(copyText.value).then(function () {
        copyButton.innerText = "Copied!";
        copyButton.classList.add("btn-success");
    }).catch(function (err) {
        console.error("Could not copy text: ", err);
    });
}