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

function openShareModal() {
    const fileUuid = document.getElementById("fileUuid").textContent.trim(); // Use UUID instead of ID
    const filePasswordInput = document.getElementById("filePassword");
    const password = filePasswordInput ? filePasswordInput.value : "";

    generateShareLink(fileUuid, password)
        .then(link => {
            const shareLinkInput = document.getElementById("shareLink");
            shareLinkInput.value = link;

            // Generate QR code for the share link
            const shareQRCode = document.getElementById("shareQRCode");
            QRCode.toCanvas(shareQRCode, encodeURI(link), {
                width: 150,
                margin: 2
            }, function (error) {
                if (error) {
                    console.error("QR Code generation failed:", error);
                }
            });

            // Show the modal
            const shareModal = new bootstrap.Modal(document.getElementById('shareModal'));
            shareModal.show();
        })
        .catch(error => {
            console.error(error);
            alert("Error generating share link.");
        });
}


function generateShareLink(fileUuid, daysValid) {
    const csrfToken = document.querySelector('meta[name="_csrf"]').content; // Retrieve CSRF token
    const expirationDate = new Date();
    expirationDate.setDate(expirationDate.getDate() + daysValid); // Add days to current date
    const expirationDateStr = expirationDate.toISOString().split('T')[0]; // Format as YYYY-MM-DD

    return fetch(`/api/file/share/${fileUuid}?expirationDate=${expirationDateStr}`, { // Include expirationDate in URL
        method: 'POST',
        credentials: 'same-origin', // Ensures cookies are sent for session
        headers: {
            'Content-Type': 'application/json',
            'X-XSRF-TOKEN': csrfToken, // Include CSRF token in headers
        },
    })
        .then((response) => {
            if (!response.ok) throw new Error("Failed to generate share link");
            return response.text();
        });
}


function copyShareLink() {
    const shareLink = document.getElementById("shareLink");
    shareLink.select();
    shareLink.setSelectionRange(0, 99999); // For mobile devices
    navigator.clipboard.writeText(shareLink.value).then(() => {
        alert("Share link copied to clipboard!");
    });
}

function createShareLink() {
    const fileUuid = document.getElementById('fileUuid').textContent.trim(); // Get the file UUID
    const daysValidInput = document.getElementById('daysValid'); // Input field for number of days
    const daysValid = parseInt(daysValidInput.value, 10); // Parse the input value as an integer

    if (isNaN(daysValid) || daysValid < 1) {
        alert("Please enter a valid number of days.");
        return;
    }

    generateShareLink(fileUuid, daysValid)
        .then((shareLink) => {
            const shareLinkInput = document.getElementById('shareLink');
            shareLinkInput.value = shareLink; // Update the input field with the generated link

            // Generate QR Code
            const qrCodeContainer = document.getElementById('shareQRCode');
            qrCodeContainer.innerHTML = ""; // Clear any existing QR code
            QRCode.toCanvas(qrCodeContainer, shareLink, {width: 150, height: 150});
        })
        .catch((error) => {
            console.error(error);
            alert("Failed to generate share link.");
        });
}
