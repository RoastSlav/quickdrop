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
    document.getElementById('linkOptions').classList.add('hidden');
    document.getElementById('generateLinkButton').disabled = true;
}

function openShareModal() {
    initializeModal();
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

    const overlay = document.getElementById('shareModal');
    const modal = document.getElementById('shareModalContent');
    overlay.classList.remove('hidden');

    // Position modal to the left of the info card
    const card = document.getElementById('fileInfoCard');
    const cardRect = card.getBoundingClientRect();
    const modalRect = modal.getBoundingClientRect();
    const space = 16; // px
    let left = cardRect.left - modalRect.width - space;
    if (left < space) left = space;
    let top = cardRect.top + cardRect.height / 2 - modalRect.height / 2;
    if (top < space) top = space;
    modal.style.left = left + 'px';
    modal.style.top = top + 'px';
}

function closeShareModal() {
    const overlay = document.getElementById('shareModal');
    const modal = document.getElementById('shareModalContent');
    overlay.classList.add('hidden');
    modal.style.left = '';
    modal.style.top = '';
    initializeModal();
}

function generateShareLink(fileUuid, daysValid, allowedNumberOfDownloads) {
    const csrfToken = document.querySelector('meta[name="_csrf"]').content;
    const expirationDate = new Date();
    expirationDate.setDate(expirationDate.getDate() + daysValid);
    const expirationDateStr = expirationDate.toISOString().split('T')[0];

    return fetch(`/api/file/share/${fileUuid}?expirationDate=${expirationDateStr}&nOfDownloads=${allowedNumberOfDownloads}`, {
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
    const allowedNumberOfDownloadsInput = document.getElementById('allowedNumberOfDownloadsCount');
    const allowedNumberOfDownloads = parseInt(allowedNumberOfDownloadsInput.value, 10);

    if (isNaN(daysValid) || daysValid < 1) {
        alert("Please enter a valid number of days.");
        return;
    }

    if (isNaN(allowedNumberOfDownloads) || allowedNumberOfDownloads < 1) {
        alert("Please enter a valid number of downloads.");
        return;
    }

    const spinner = document.getElementById('spinner');
    const generateLinkButton = document.getElementById('generateLinkButton');

    spinner.style.display = 'inline-block';
    generateLinkButton.disabled = true;

    generateShareLink(fileUuid, daysValid, allowedNumberOfDownloads)
        .then((shareLink) => {
            updateShareLink(shareLink); // Update with the token-based link
        })
        .catch((error) => {
            console.error(error);
            alert("Failed to generate share link.");
        }).finally(() => {
        spinner.style.display = 'none';
        generateLinkButton.disabled = false;
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
    const linkOptions = document.getElementById('linkOptions');
    const generateLinkButton = document.getElementById('generateLinkButton');

    if (unrestrictedLinkCheckbox.checked) {
        linkOptions.classList.remove('hidden');
        generateLinkButton.disabled = false;
    } else {
        linkOptions.classList.add('hidden');
        generateLinkButton.disabled = true;
        initializeModal();
    }
}
