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

function initializeModal() {
    const downloadLink = document.getElementById("downloadLink").innerText;
    updateShareLink(downloadLink);
    document.getElementById('unrestrictedLink').checked = false;
    document.getElementById('daysValidContainer').style.display = 'none';
    document.getElementById('generateLinkButton').disabled = true;
}

function openShareModal() {
    const downloadLink = document.getElementById("downloadLink").innerText;

    const shareLinkInput = document.getElementById("shareLink");
    shareLinkInput.value = downloadLink;

    const shareQRCode = document.getElementById("shareQRCode");
    QRCode.toCanvas(shareQRCode, encodeURI(downloadLink), {
        width: 150,
        margin: 2
    }, function (error) {
        if (error) {
            console.error("QR Code generation failed:", error);
        }
    });

    const shareModal = new bootstrap.Modal(document.getElementById('shareModal'));
    shareModal.show();
}

function generateShareLink(fileUuid, daysValid) {
    const csrfToken = document.querySelector('meta[name="_csrf"]').content;
    const expirationDate = new Date();
    expirationDate.setDate(expirationDate.getDate() + daysValid);
    const expirationDateStr = expirationDate.toISOString().split('T')[0];

    return fetch(`/api/file/share/${fileUuid}?expirationDate=${expirationDateStr}`, {
        method: 'POST',
        credentials: 'same-origin',
        headers: {
            'Content-Type': 'application/json',
            'X-XSRF-TOKEN': csrfToken,
        },
    })
        .then((response) => {
            if (!response.ok) throw new Error("Failed to generate share link");
            return response.text();
        });
}


function copyShareLink() {
    const shareLinkInput = document.getElementById('shareLink');
    navigator.clipboard.writeText(shareLinkInput.value)
        .then(() => {
            alert("Link copied to clipboard!");
        })
        .catch((err) => {
            console.error("Failed to copy link:", err);
        });
}

function createShareLink() {
    const fileUuid = document.getElementById('fileUuid').textContent.trim();
    const daysValidInput = document.getElementById('daysValid');
    const daysValid = parseInt(daysValidInput.value, 10);

    if (isNaN(daysValid) || daysValid < 1) {
        alert("Please enter a valid number of days.");
        return;
    }

    generateShareLink(fileUuid, daysValid)
        .then((shareLink) => {
            updateShareLink(shareLink); // Update with the token-based link
        })
        .catch((error) => {
            console.error(error);
            alert("Failed to generate share link.");
        });
}

function updateShareLink(link) {
    const shareLinkInput = document.getElementById('shareLink');
    const qrCodeContainer = document.getElementById('shareQRCode');

    shareLinkInput.value = link;
    qrCodeContainer.innerHTML = '';
    QRCode.toCanvas(qrCodeContainer, link, {width: 150, height: 150});
}


function toggleLinkType() {
    const unrestrictedLinkCheckbox = document.getElementById('unrestrictedLink');
    const daysValidContainer = document.getElementById('daysValidContainer');
    const generateLinkButton = document.getElementById('generateLinkButton');

    if (unrestrictedLinkCheckbox.checked) {
        daysValidContainer.style.display = 'block';
        generateLinkButton.disabled = false;
    } else {
        daysValidContainer.style.display = 'none';
        generateLinkButton.disabled = true;
        initializeModal();
    }
}
