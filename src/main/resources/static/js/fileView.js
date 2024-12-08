function copyToClipboard(button) {
    const copyText = document.getElementById("downloadLink");

    navigator.clipboard.writeText(copyText.value)
        .then(() => {
            button.innerText = "Copied!";
            button.classList.add("copied");

            // Revert back after 2 seconds
            setTimeout(() => {
                button.innerText = "Copy Link";
                button.classList.remove("copied");
            }, 2000);
        })
        .catch((err) => {
            console.error("Could not copy text: ", err);
            button.innerText = "Failed!";
            button.classList.add("btn-danger");

            setTimeout(() => {
                button.innerText = "Copy Link";
                button.classList.remove("btn-danger");
            }, 2000);
        });
}

function showPreparingMessage() {
    document.getElementById('preparingMessage').style.display = 'block';
}

document.addEventListener("DOMContentLoaded", function () {
    const downloadLink = document.getElementById("downloadLink").value; // Get the file download link
    const qrCodeContainer = document.getElementById("qrCodeContainer"); // Container for the QR code

    console.log("Download link:", downloadLink); // Debugging log

    if (downloadLink) {
        QRCode.toCanvas(qrCodeContainer, encodeURI(downloadLink), {
            width: 100, // Size of the QR Code
            margin: 2 // Margin around the QR Code
        }, function (error) {
            if (error) {
                console.error("QR Code generation failed:", error);
            }
        });
    } else {
        console.error("Download link is empty or undefined.");
    }
});

function confirmDelete() {
    return confirm("Are you sure you want to delete this file? This action cannot be undone.");
}

function updateCheckboxState(event, checkbox) {
    event.preventDefault();
    const hiddenField = checkbox.form.querySelector('input[name="keepIndefinitely"][type="hidden"]');
    if (hiddenField) {
        hiddenField.value = checkbox.checked;
    }

    console.log('Submitting form...');
    checkbox.form.submit();
}